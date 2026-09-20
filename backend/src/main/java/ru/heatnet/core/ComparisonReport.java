package ru.heatnet.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Small, UTF-8 CSV comparison. Computed fields only; no uploaded text becomes a formula. */
public final class ComparisonReport {
    private ComparisonReport() {}
    public static String csv(JsonNode job) {
        StringBuilder out=new StringBuilder("\uFEFFМесто;Вариант;Подключено;Всего объектов;Строительные работы, руб.;Штраф, руб.;Итог, руб.;Новая сеть, м;Реконструкция, м;Оценка;Проверки;Реконструкция оценена;Неподключённые ОКС\r\n");
        for(JsonNode variant:job.path("variants")) {
            JsonNode summary=variant.path("summary");
            StringBuilder ids=new StringBuilder();
            for(JsonNode id:summary.path("unconnected_oks_ids")) {if(ids.length()>0) ids.append(", ");ids.append(id.asText());}
            double works=summary.path("calculated_cost").asDouble()-summary.path("unconnected_penalty").asDouble();
            out.append(summary.path("rank").asInt()).append(';').append(cell(variant.path("name").asText())).append(';')
                .append(variant.path("connected_count").asInt()).append(';').append(variant.path("total_count").asInt()).append(';')
                .append(n(works,2)).append(';').append(n(summary.path("unconnected_penalty").asDouble(),2)).append(';')
                .append(n(summary.path("calculated_cost").asDouble(),2)).append(';').append(n(summary.path("new_network_length").asDouble(),2)).append(';')
                .append(variant.path("cost_complete").asBoolean()?n(summary.path("reconstruction_length").asDouble(),2):"не оценена").append(';')
                .append(n(summary.path("score").asDouble(),5)).append(';')
                .append(variant.path("checks_passed").asBoolean()?"пройдены реализованные проверки":"есть нарушения").append(';')
                .append(variant.path("cost_complete").asBoolean()?"да":"нет").append(';').append(cell(ids.toString())).append("\r\n");
        }
        out.append("\r\nПримечание;Эскизный расчёт. Не является готовым проектом. Неоценённая реконструкция не включена в стоимость.\r\n");
        return out.toString();
    }
    private static String n(double value,int digits) {return BigDecimal.valueOf(value).setScale(digits,RoundingMode.HALF_UP).toPlainString().replace('.',',');}
    static String cell(String value) {
        if(value.matches("^[\\s]*[=+@-].*")||value.startsWith("\t")||value.startsWith("\r")) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}
