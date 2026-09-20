'use strict';
const $ = id => document.getElementById(id);
const escapeText = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const number = (value, digits = 0) => new Intl.NumberFormat('ru-RU', {maximumFractionDigits:digits}).format(value);
const money = value => number(value / 1e6, 2) + ' млн ₽';
const state = {mode:'demo', dataset:null, source:null, job:null, result:null, variant:null, busy:false, epoch:0};
let map, tileLayer, lastBounds, toastTimer;
const groups = {};
const layerRefs = new Map();
const typeNames = {source:'Источник тепла', heat_network:'Тепловая сеть', heat_chamber:'Тепловая камера', oks_future:'Перспективный ОКС', oks_existing:'Существующее здание', oks_connection_point:'Точка подключения', restriction:'Ограничение', tie_in:'Точка врезки', technical_node:'Технический узел', heat_network_reconstruction:'Реконструкция сети', heat_chamber_reconstruction:'Реконструкция камеры'};
const restrictionNames = {oks:'Здание', water:'Водный объект', railway:'Железная дорога', road:'Автодорога', park:'Парк', gas_pipeline:'Газопровод', power_cable:'Силовой кабель', tram_tracks:'Трамвайные пути', prohibited_site:'Запрещённая территория', social_area:'Социальный объект'};

function toast(message) {
  $('toast').textContent = message; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 6500);
}
async function api(path, options = {}) {
  const response = await fetch('/api/v1' + path, options);
  const text = await response.text(); let data;
  try { data = JSON.parse(text); } catch { throw new Error('Сервер вернул неожиданный ответ. Проверьте подключение.'); }
  if (!response.ok) throw new Error(data.message || 'Не удалось выполнить запрос');
  return data;
}
function busy(value) {
  state.busy = value;
  for (const id of ['upload-button','sample-button','empty-sample-button']) $(id).disabled = value;
  $('history-button').disabled=value;
  document.querySelectorAll('[data-mode]').forEach(button => button.disabled = value);
  $('calculate-button').disabled = value || !state.dataset || state.dataset.status !== 'READY';
}
function setupMap() {
  if (!window.L) {toast('Не удалось загрузить библиотеку карты. Обновите страницу.'); return;}
  map = L.map('map', {zoomControl:false, preferCanvas:true}).setView([55.699,37.640],15);
  L.control.zoom({position:'topright'}).addTo(map);
  L.control.scale({position:'bottomleft', imperial:false}).addTo(map);
  tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom:19, attribution:'© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>'});
  tileLayer.on('tileerror', () => {if($('basemap-toggle').checked) $('map-coordinates').textContent='Подложка недоступна · геометрия набора остаётся видимой';});
  for (const name of ['network','restrictions','connections','result']) groups[name] = L.layerGroup().addTo(map);
  map.on('mousemove', event => $('map-coordinates').textContent = event.latlng.lat.toFixed(5) + '° N, ' + event.latlng.lng.toFixed(5) + '° E');
  new ResizeObserver(() => map.invalidateSize()).observe($('map'));
}
function featureStyle(feature, output = false) {
  const p = feature.properties;
  if (output) {
    if(p.object_type.includes('reconstruction')) return {color:'#a36298',weight:5,opacity:.95,dashArray:'7 5'};
    return {color:'#df8349',weight:4.5,opacity:.96,lineCap:'round',lineJoin:'round',dashArray:p.laying_method==='special'?'7 4':null,className:'result-network'};
  }
  if (p.object_type==='heat_network') return {color:'#69899a',weight:3,opacity:.8};
  if (p.restriction_type==='water') return {color:'#a5c6cf',weight:1,fillColor:'#b5d5dc',fillOpacity:.72};
  if (p.restriction_type==='railway') return {color:'#a6a095',weight:1,fillColor:'#c7c2b8',fillOpacity:.4,dashArray:'5 4'};
  if (p.restriction_type==='park') return {color:'#b0c7aa',weight:1,fillColor:'#c6dbc0',fillOpacity:.5};
  return {color:'#b7c1c6',weight:1,fillColor:'#d5dbdc',fillOpacity:.7};
}
function pointStyle(feature, output) {
  const type=feature.properties.object_type;
  if(type==='source') return {radius:7,color:'#fff',weight:2,fillColor:'#344f60',fillOpacity:1};
  if(type==='oks_connection_point') {
    const missing=state.variant?.summary.unconnected_oks_ids.map(String).includes(String(feature.properties.oks_id??feature.properties.id));
    return {radius:missing?7:5.5,color:missing?'#b94e49':'#fffaf3',weight:2,fillColor:missing?'#fff0ed':'#e0945c',fillOpacity:1};
  }
  if(type==='tie_in') return {radius:5,color:'#b36632',weight:2,fillColor:'#fff5e8',fillOpacity:1};
  if(type==='technical_node') return {radius:2.5,color:'#df8349',weight:1,fillColor:'#fff',fillOpacity:1};
  return {radius:output?4:3.5,color:output?'#c07744':'#66889a',weight:1.5,fillColor:output?'#fff8f0':'#f3f7f8',fillOpacity:1};
}
function inspect(feature, output) {
  const p=feature.properties;const label=p.restriction_type ? (restrictionNames[p.restriction_type] || p.restriction_type) : (typeNames[p.object_type] || p.object_type);
  const fields=[['id','ID'],['address','Адрес'],['name','Название'],['flow_tph','Расход, т/ч'],['diameter','Диаметр, мм'],['length','Длина, м'],['cost','Стоимость'],['existing_diameter','Исходный ДУ, мм'],['required_diameter','Требуемый ДУ, мм'],['added_flow_tph','Новый расход, т/ч'],['calculated_flow_tph','Итоговый расход, т/ч'],['laying_method','Прокладка']];
  const rows=fields.filter(([key])=>p[key]!==undefined&&p[key]!==null).map(([key,title])=> {
    let value=p[key];
    if(key==='cost') value=money(value);
    else if(key==='laying_method') value=value==='special'?'Специальный проход':'Бесканальная';
    else if(typeof value==='number'&&key!=='id') value=number(value,2);
    return '<div class="inspector-row"><span>'+title+'</span><strong>'+escapeText(value)+'</strong></div>';
  }).join('');
  $('inspector-content').innerHTML='<div class="eyebrow">'+(output?'ПРОЕКТИРУЕМАЯ СЕТЬ':'ИСХОДНЫЕ ДАННЫЕ')+'</div><h3>'+escapeText(label)+'</h3>'+rows;
  $('inspector').hidden=false;
}
function addFeature(feature, group, output=false) {
  if (!feature.geometry || !map) return;
  const layer=L.geoJSON(feature, {style:f=>featureStyle(f,output),pointToLayer:(f,latlng)=>L.circleMarker(latlng,pointStyle(f,output)),onEachFeature:(f,l)=> {
    l.on('click',()=>inspect(f,output));
    const p=f.properties;
    const label=document.createElement('span');
    label.textContent=(typeNames[p.object_type]||restrictionNames[p.restriction_type]||'Объект')+' · '+p.id;
    l.bindTooltip(label,{sticky:true});
    if(p.object_type==='source') {
      const text=document.createElement('span');text.textContent=p.name||'Источник';
      l.bindTooltip(text,{permanent:true,direction:'top',offset:[0,-8],className:'source-label'});
    }
  }});
  group.addLayer(layer);
  layerRefs.set((output?'output:':'input:')+String(feature.properties.id),{layer,feature,output,group});
}
function drawSource() {
  if(!map) return;
  Object.values(groups).forEach(group=>group.clearLayers());
  layerRefs.clear();
  for(const feature of state.source.features) {
    const type=feature.properties.object_type;
    const group=['heat_network','heat_chamber','source'].includes(type)?groups.network:type==='oks_connection_point'?groups.connections:groups.restrictions;
    addFeature(feature,group);
  }
  lastBounds=L.geoJSON(state.source).getBounds();
  fit();$('empty-map').hidden=true;
}
function fit() {
  if(map&&lastBounds?.isValid()) {
    map.invalidateSize();
    map.fitBounds(lastBounds,{padding:[Math.min(40,map.getSize().x/10),45],maxZoom:17,animate:false});
  }
}
function diagnostics(meta) {
  const entries=[...meta.errors.map(message=>({message,error:true})),...meta.warnings.map(message=>({message,error:false}))];
  $('diagnostics').hidden=!entries.length;
  $('diagnostic-count').textContent=entries.length;
  $('diagnostic-list').innerHTML=entries.map(entry=>'<p class="'+(entry.error?'error':'')+'">'+escapeText(entry.message)+'</p>').join('');
  $('diagnostics').open=meta.status==='INVALID';
}
function clearResults() {
  state.job=null;state.result=null;state.variant=null;
  $('variants-list').replaceChildren();$('variants-count').textContent='0';
  $('result-empty').hidden=false;$('cost-breakdown').hidden=true;$('route-notes').hidden=true;
  $('download-button').disabled=true;$('inspector').hidden=true;
  $('compare-button').disabled=true;$('csv-button').disabled=true;$('connection-issues').hidden=true;
  $('search-results').hidden=true;
  for(const key of layerRefs.keys()) if(key.startsWith('output:')) layerRefs.delete(key);
  $('metric-length').innerHTML='— <em>м</em>';$('metric-cost').innerHTML='— <em>млн ₽</em>';
  $('metric-length-note').textContent='после построения маршрутов';$('metric-cost-note').textContent='после расчёта';
  if(groups.result) groups.result.clearLayers();
  localStorage.removeItem('heatnet.job');
}
async function acceptDataset(meta) {
  const source=await api('/datasets/'+meta.id+'/features');
  clearResults();state.dataset=meta;state.source=source;
  $('file-card').hidden=false;$('file-name').textContent=meta.name;
  $('file-meta').textContent=meta.feature_count+' объектов · '+number(meta.size_bytes/1024,0)+' КБ';
  $('file-status').textContent=meta.status==='READY'?'✓':'!';
  $('metric-objects').textContent=meta.connection_count;
  $('metric-objects-note').textContent=meta.status==='READY'?'доступны для расчёта':'проверьте исходные данные';
  $('metric-flow').innerHTML=number(meta.total_flow_tph,2)+' <em>т/ч</em>';
  $('workspace-title').textContent=meta.name.replace(/\.geojson$/i,'');
  diagnostics(meta);drawSource();
  $('object-search').disabled=false;$('object-search').value='';
  $('notice').classList.toggle('warning',!meta.reconstruction_known);
  $('notice-text').textContent=!meta.reconstruction_known?'Нет данных о загрузке старой сети. Реконструкция не оценена, стоимость будет неполной.':'Эскизное моделирование. Результат требует инженерной проверки.';
  localStorage.setItem('heatnet.dataset',meta.id);
}
async function sample() {
  if(state.busy) return;
  busy(true);$('sample-button').querySelector('span:nth-child(2)').firstChild.textContent='Загрузка примера…';
  try {await acceptDataset(await api('/datasets/sample?mode='+state.mode,{method:'POST'}));}
  catch(error) {toast(error.message);}
  finally {busy(false);$('sample-button').querySelector('span:nth-child(2)').firstChild.textContent='Открыть конкурсный набор';}
}
async function upload(file) {
  if(!file||state.busy) return;
  if(file.size>20*1024*1024) {toast('В этой версии можно загрузить файл до 20 МБ.');return;}
  busy(true);const form=new FormData();form.append('file',file);
  try {await acceptDataset(await api('/datasets?mode='+state.mode,{method:'POST',body:form}));}
  catch(error) {toast(error.message);}
  finally {busy(false);$('file-input').value='';}
}
async function setMode(mode) {
  if(state.busy||state.mode===mode) return;
  updateMode(mode);
  if(state.source) {
    const file=new File([JSON.stringify(state.source)],state.dataset.name,{type:'application/geo+json'});
    await upload(file);
  }
}
function updateMode(mode) {
  state.mode=mode;
  document.querySelectorAll('[data-mode]').forEach(button=>{button.classList.toggle('active',button.dataset.mode===mode);button.setAttribute('aria-pressed',String(button.dataset.mode===mode));});
  $('mode-description').textContent=mode==='demo'?'Для неполных данных. Все допущения будут перечислены в результате.':'Проверка обязательных атрибутов и связей. Неполные данные блокируют расчёт.';
  $('mode-badge').textContent=mode==='demo'?'Демонстрационный режим':'Строгая проверка данных';
  $('mode-badge').classList.toggle('strict',mode==='strict');
}
async function calculate() {
  if(!state.dataset||state.busy) return;
  clearResults();busy(true);$('progress-overlay').hidden=false;
  const epoch=++state.epoch;
  try {
    const job=await api('/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({datasetId:state.dataset.id,maxVariants:3})});
    localStorage.setItem('heatnet.job',job.id);
    await poll(job.id,epoch);
  } catch(error) {toast(error.message);}
  finally {if(epoch===state.epoch){busy(false);$('progress-overlay').hidden=true;}}
}
async function poll(id,epoch) {
  while(epoch===state.epoch) {
    const job=await api('/jobs/'+id);state.job=job;
    $('progress-stage').textContent=job.stage||'В очереди';$('progress-bar').style.width=(job.progress||3)+'%';
    if(job.status==='FAILED') throw new Error(job.message||'Расчёт не выполнен');
    if(['SUCCEEDED','PARTIAL','REVIEW_REQUIRED'].includes(job.status)) {
      state.result=await api('/jobs/'+id+'/result');renderVariants();return;
    }
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
}
function renderVariants() {
  const variants=state.job.variants||[];
  $('result-empty').hidden=variants.length>0;$('variants-count').textContent=variants.length;
  $('variants-list').replaceChildren();
  variants.forEach(variant=> {
    const summary=variant.summary;const button=document.createElement('button');button.className='variant';button.dataset.variant=variant.variant_id;
    button.innerHTML='<div class="variant-top"><span class="variant-index">'+summary.rank+'</span>'+escapeText(variant.name)+'</div><div class="variant-cost">'+number(summary.calculated_cost/1e6,2)+' <em>млн ₽</em></div><div class="variant-meta"><span>'+number(summary.new_network_length,0)+' м новой сети</span><span>'+variant.connected_count+'/'+variant.total_count+' объектов</span></div>'+(!variant.checks_passed?'<div class="variant-warning">Есть нарушения · нужна доработка</div>':!variant.cost_complete?'<div class="variant-warning">Без оценки реконструкции</div>':'')+'<div class="variant-score">ОЦЕНКА '+number(summary.score,3)+' · '+variant.tie_in_count+' врезок</div>';
    button.addEventListener('click',()=>selectVariant(variant));$('variants-list').appendChild(button);
  });
  if(variants.length) selectVariant(variants[0]);
  $('download-button').disabled=!variants.length;
  $('compare-button').disabled=!variants.length;$('csv-button').disabled=!variants.length;
  $('export-note').textContent='GeoJSON с пометкой MVP и допущениями';
}
function selectVariant(variant) {
  state.variant=variant;const summary=variant.summary;
  document.querySelectorAll('.variant').forEach(button=>{button.classList.toggle('selected',button.dataset.variant===variant.variant_id);button.setAttribute('aria-pressed',String(button.dataset.variant===variant.variant_id));});
  if(groups.result) {
    groups.result.clearLayers();
    for(const key of layerRefs.keys()) if(key.startsWith('output:')) layerRefs.delete(key);
    state.result.features.filter(feature=>feature.properties.variant_id===variant.variant_id).forEach(feature=>addFeature(feature,groups.result,true));
    groups.connections.clearLayers();
    state.source.features.filter(feature=>feature.properties.object_type==='oks_connection_point').forEach(feature=>addFeature(feature,groups.connections));
  }
  $('metric-objects').textContent=variant.connected_count+' / '+variant.total_count;
  $('metric-objects-note').textContent=summary.unconnected_oks_ids.length?'не подключены: '+summary.unconnected_oks_ids.join(', '):'подключены все объекты';
  $('metric-length').innerHTML=number(summary.new_network_length,0)+' <em>м</em>';
  $('metric-length-note').textContent='реконструкция: '+(variant.cost_complete?number(summary.reconstruction_length,0)+' м':'не оценена');
  $('metric-cost').innerHTML=number(summary.calculated_cost/1e6,2)+' <em>млн ₽</em>';
  $('metric-cost-note').textContent=variant.cost_complete?'включая реконструкцию и штрафы':'без оценки реконструкции';
  $('notice').classList.toggle('warning',!variant.checks_passed||!variant.cost_complete);
  $('notice-text').textContent=!variant.checks_passed?'В выбранном варианте есть нарушения. Откройте «Проверки и ограничения».':!variant.cost_complete?'Реконструкция не оценена: стоимость неполная. Допущения доступны в проверке данных.':'Выбранный вариант прошёл реализованные проверки. Требуется инженерная проверка проекта.';
  const rows=[['construction_cost','Новые трубы'],['chamber_construction_cost','Новые камеры'],['tie_in_cost','Врезки'],['reconstruction_cost','Реконструкция труб'],['chamber_reconstruction_cost','Реконструкция камер'],['unconnected_penalty','Штраф за неподключение']];
  $('cost-breakdown').innerHTML='<div class="cost-heading">Из чего складывается стоимость</div>'+rows.map(([key,label])=>'<div class="cost-row '+(key==='unconnected_penalty'&&summary[key]>0?'penalty':'')+'"><span>'+label+'</span><span>'+(!variant.cost_complete&&key.includes('reconstruction')?'не оценена':money(summary[key]))+'</span></div>').join('');
  $('cost-breakdown').hidden=false;
  const notes=[...(variant.violations||[]),...(variant.notes||[]),...(state.job.metadata?.limitations||[])];
  if(summary.unconnected_oks_ids.length) notes.unshift('Маршрут не найден для ОКС: '+summary.unconnected_oks_ids.join(', '));
  $('route-notes').hidden=false;$('route-notes').open=!variant.checks_passed;
  $('route-notes-content').innerHTML=notes.map(note=>'<p>'+escapeText(note)+'</p>').join('');
  renderConnectionIssues(variant);
}

function focusObject(key) {
  const ref=layerRefs.get(key);if(!ref||!map) return;
  if(!map.hasLayer(ref.group)) {
    ref.group.addTo(map);
    for(const [name,group] of Object.entries(groups)) if(group===ref.group) {const checkbox=document.querySelector('[data-layer="'+name+'"]');if(checkbox)checkbox.checked=true;}
  }
  const bounds=ref.layer.getBounds();
  map.fitBounds(bounds,{padding:[45,65],maxZoom:18,animate:false});
  inspect(ref.feature,ref.output);$('search-results').hidden=true;
  $('map').scrollIntoView({block:'nearest',behavior:'smooth'});
}
function searchObjects() {
  const query=$('object-search').value.trim().toLocaleLowerCase('ru-RU');
  const box=$('search-results');box.replaceChildren();box.hidden=!query;
  if(!query) return;
  const found=[...layerRefs.entries()].filter(([,ref])=>{
    const p=ref.feature.properties;
    return [p.id,p.address,p.name,typeNames[p.object_type],restrictionNames[p.restriction_type]].some(value=>String(value??'').toLocaleLowerCase('ru-RU').includes(query));
  }).sort((a,b)=>Number(String(b[1].feature.properties.id).toLowerCase()===query)-Number(String(a[1].feature.properties.id).toLowerCase()===query)).slice(0,8);
  if(!found.length) {const empty=document.createElement('p');empty.textContent='Объекты не найдены';box.appendChild(empty);return;}
  for(const [key,ref] of found) {
    const p=ref.feature.properties,button=document.createElement('button');
    button.type='button';button.innerHTML='<strong>'+escapeText(typeNames[p.object_type]||p.object_type)+' · '+escapeText(p.id)+'</strong><small>'+escapeText(p.address||p.name||(ref.output?'Результат расчёта':'Исходные данные'))+'</small>';
    button.addEventListener('click',()=>focusObject(key));box.appendChild(button);
  }
}
function renderConnectionIssues(variant) {
  const box=$('connection-issues'),ids=variant.summary.unconnected_oks_ids;
  box.replaceChildren();box.hidden=!ids.length;
  if(!ids.length) return;
  const heading=document.createElement('strong');heading.textContent='Без маршрута · '+ids.length;box.appendChild(heading);
  const hint=document.createElement('p');hint.textContent='Нажмите на объект, чтобы найти его на карте.';box.appendChild(hint);
  const chips=document.createElement('div');chips.className='issue-chips';
  for(const id of ids) {
    const point=state.source.features.find(feature=>feature.properties.object_type==='oks_connection_point'&&String(feature.properties.oks_id??feature.properties.id)===String(id));
    const button=document.createElement('button');button.textContent='ОКС '+id;button.disabled=!point;
    const reason=variant.unconnected_reasons?.[id];button.title=reason||'Автоматический маршрут не найден';
    button.addEventListener('click',()=>{focusObject('input:'+point.properties.id);if(reason)toast(reason);});chips.appendChild(button);
  }
  box.appendChild(chips);
}
function openComparison() {
  if(!state.job?.variants?.length) return;
  const variants=state.job.variants;
  const rows=[
    ['Подключённые объекты',v=>v.connected_count+' / '+v.total_count],
    ['Расходы на работы',v=>money(v.summary.calculated_cost-v.summary.unconnected_penalty)],
    ['Новые трубы',v=>money(v.summary.construction_cost)],['Новые камеры',v=>money(v.summary.chamber_construction_cost)],
    ['Врезки',v=>money(v.summary.tie_in_cost)],['Реконструкция труб',v=>v.cost_complete?money(v.summary.reconstruction_cost):'Не оценена'],
    ['Реконструкция камер',v=>v.cost_complete?money(v.summary.chamber_reconstruction_cost):'Не оценена'],
    ['Штраф за неподключение',v=>money(v.summary.unconnected_penalty)],
    ['Итого для ранжирования',v=>money(v.summary.calculated_cost)],
    ['Длина новой сети',v=>number(v.summary.new_network_length,1)+' м'],
    ['Длина реконструкции',v=>v.cost_complete?number(v.summary.reconstruction_length,1)+' м':'Не оценена'],
    ['Вклад стоимости в оценку',v=>number(.7*v.summary.calculated_cost/25000000,3)],
    ['Вклад длины в оценку',v=>number(.3*v.summary.length/100,3)],
    ['Итоговая оценка',v=>number(v.summary.score,3)],
    ['Реализованные проверки',v=>v.checks_passed?'Пройдены':'Есть нарушения']
  ];
  $('comparison-table').innerHTML='<table><thead><tr><th scope="col">Показатель</th>'+variants.map(v=>'<th scope="col">'+escapeText(v.name)+'<small>Место '+v.summary.rank+'</small></th>').join('')+'</tr></thead><tbody>'+rows.map(([label,get])=>'<tr><th scope="row">'+label+'</th>'+variants.map(v=>'<td>'+escapeText(get(v))+'</td>').join('')+'</tr>').join('')+'</tbody></table>';
  $('comparison-dialog').showModal();
}
async function openHistory() {
  $('history-dialog').showModal();$('history-list').textContent='Загрузка…';
  try {
    const jobs=await api('/jobs');$('history-list').replaceChildren();
    if(!jobs.length) {$('history-list').textContent='Пока нет расчётов. Загрузите данные и постройте первый вариант.';return;}
    const statuses={SUCCEEDED:'Завершён',PARTIAL:'Частичное подключение',REVIEW_REQUIRED:'Нужна доработка',FAILED:'Ошибка',RUNNING:'Выполняется',QUEUED:'В очереди'};
    for(const job of jobs) {
      const button=document.createElement('button');button.className='history-item';
      const date=new Date(job.created_at).toLocaleString('ru-RU',{dateStyle:'short',timeStyle:'short'});
      button.innerHTML='<div><strong>'+escapeText(job.dataset_name)+'</strong><small>'+escapeText(date)+' · '+escapeText(job.mode==='demo'?'Демо':'Строгий')+' · v'+escapeText(job.engine_version)+'</small></div><div class="history-meta"><span>'+escapeText(statuses[job.status]||job.status)+'</span><small>'+ (job.variant_count?job.connected_count+'/'+job.total_count+' объектов · '+money(job.best_summary.calculated_cost):'')+'</small></div><span>↗</span>';
      button.addEventListener('click',()=>loadHistory(job));$('history-list').appendChild(button);
    }
  } catch(error) {$('history-list').textContent=error.message;}
}
async function loadHistory(job) {
  if(state.busy) return;
  $('history-dialog').close();busy(true);
  try {
    const dataset=await api('/datasets/'+job.dataset_id);updateMode(dataset.mode);await acceptDataset(dataset);
    localStorage.setItem('heatnet.job',job.id);$('progress-overlay').hidden=false;
    await poll(job.id,++state.epoch);
    toast('Открыт сохранённый расчёт'+(job.engine_version==='0.1'?' предыдущей версии. Можно пересчитать.':'.'));
  } catch(error) {toast(error.message);}
  finally {busy(false);$('progress-overlay').hidden=true;}
}
async function restore() {
  try {
    const meta=await api('/meta');$('server-state').classList.add('online');$('server-state').innerHTML='<i></i>Сервис доступен';
    if(!meta.routing_available) {toast('Сервер ещё не поддерживает расчёт. Обновите backend.');return;}
    const datasetId=localStorage.getItem('heatnet.dataset'),jobId=localStorage.getItem('heatnet.job');
    if(datasetId) {
      busy(true);const dataset=await api('/datasets/'+datasetId);updateMode(dataset.mode);await acceptDataset(dataset);
      if(jobId) {
        try {localStorage.setItem('heatnet.job',jobId);$('progress-overlay').hidden=false;await poll(jobId,++state.epoch);}
        catch(error) {localStorage.removeItem('heatnet.job');toast(error.message);}
      }
    }
  } catch(error) {
    if(!state.dataset) {localStorage.removeItem('heatnet.dataset');localStorage.removeItem('heatnet.job');}
    toast(error.message);
    if(!$('server-state').classList.contains('online')) $('server-state').innerHTML='<i></i>Сервис недоступен';
  } finally {busy(false);$('progress-overlay').hidden=true;}
}

setupMap();
$('sample-button').addEventListener('click',sample);$('empty-sample-button').addEventListener('click',sample);
$('upload-button').addEventListener('click',()=>$('file-input').click());
$('file-input').addEventListener('change',event=>upload(event.target.files[0]));
$('upload-button').addEventListener('dragover',event=>{event.preventDefault();$('upload-button').classList.add('dragover');});
$('upload-button').addEventListener('dragleave',()=>$('upload-button').classList.remove('dragover'));
$('upload-button').addEventListener('drop',event=>{event.preventDefault();$('upload-button').classList.remove('dragover');upload(event.dataTransfer.files[0]);});
$('calculate-button').addEventListener('click',calculate);
$('object-search').addEventListener('input',searchObjects);
$('object-search').addEventListener('keydown',event=>{if(event.key==='Enter')$('search-results').querySelector('button')?.click();if(event.key==='Escape')$('search-results').hidden=true;});
$('compare-button').addEventListener('click',openComparison);
$('history-button').addEventListener('click',openHistory);
$('csv-button').addEventListener('click',()=>{if(state.job){const link=document.createElement('a');link.href='/api/v1/jobs/'+state.job.id+'/report.csv';document.body.appendChild(link);link.click();link.remove();}});
document.querySelectorAll('[data-close]').forEach(button=>button.addEventListener('click',()=>$(button.dataset.close).close()));
$('download-button').addEventListener('click',()=>{if(state.job) {const link=document.createElement('a');link.href='/api/v1/jobs/'+state.job.id+'/result';link.download='heatnet.geojson';document.body.appendChild(link);link.click();link.remove();}});
$('fit-button').addEventListener('click',fit);$('close-inspector').addEventListener('click',()=>$('inspector').hidden=true);
document.addEventListener('keydown',event=>{if(event.key==='Escape') $('inspector').hidden=true;});
document.querySelectorAll('[data-mode]').forEach(button=>button.addEventListener('click',()=>setMode(button.dataset.mode)));
document.querySelectorAll('[data-layer]').forEach(input=>input.addEventListener('change',()=>{if(!map)return;const group=groups[input.dataset.layer];if(input.checked)group.addTo(map);else map.removeLayer(group);}));
$('basemap-toggle').addEventListener('change',event=>{if(!map)return;if(event.target.checked)tileLayer.addTo(map);else map.removeLayer(tileLayer);});
restore();
