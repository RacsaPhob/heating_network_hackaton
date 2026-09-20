package ru.heatnet.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonReportTest {
    @Test void escapesCsvAndBlocksFormulaInjectionInIdentifiersAndNames() {
        assertEquals("\"'=1+1\"",ComparisonReport.cell("=1+1"));
        assertEquals("\"'  +SUM(A1)\"",ComparisonReport.cell("  +SUM(A1)"));
        assertEquals("\"a;\"\"b\"\"\"",ComparisonReport.cell("a;\"b\""));
    }
    @Test void separatesConstructionFromPenaltyAndUnknownReconstruction() {
        ObjectMapper mapper=new ObjectMapper();
        Object summary=Geo.map("rank",1,"calculated_cost",120000000,"unconnected_penalty",100000000,
            "new_network_length",100,"reconstruction_length",0,"score",3.66,"unconnected_oks_ids",java.util.List.of("=1+1"));
        Object variant=Geo.map("name","Тест","summary",summary,"connected_count",1,"total_count",2,"checks_passed",true,"cost_complete",false);
        String csv=ComparisonReport.csv(mapper.valueToTree(Geo.map("variants",java.util.List.of(variant))));
        assertTrue(csv.contains("20000000,00;100000000,00;120000000,00"));
        assertTrue(csv.contains("не оценена"));assertTrue(csv.contains("\"'=1+1\""));
    }
}
