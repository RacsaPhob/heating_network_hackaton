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
    @Test void separatesConstructionFromPenaltyAndPreservesCsvEscaping() {
        ObjectMapper mapper=new ObjectMapper();
        Object summary=Geo.map("rank",1,"calculated_cost",120000000,"construction_cost",20000000,
            "chamber_construction_cost",3000000,"existing_chamber_tie_in_count",1,
            "existing_chamber_tie_in_cost",5000000,"unconnected_penalty",100000000,
            "new_network_length",100,"score",3.66,"unconnected_oks_ids",java.util.List.of("=1+1"));
        Object variant=Geo.map("name","Тест","summary",summary,"connected_count",1,"total_count",2,"checks_passed",true);
        String csv=ComparisonReport.csv(mapper.valueToTree(Geo.map("variants",java.util.List.of(variant))));
        assertTrue(csv.contains("20000000,00;3000000,00;1;5000000,00;100000000,00;120000000,00"));
        assertTrue(csv.contains("\"'=1+1\""));
    }
}
