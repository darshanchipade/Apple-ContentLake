package com.apple.springboot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class CountTest {
    @Test
    public void runCount() throws Exception {
        String json = Files.readString(Paths.get(
                "/Users/darshanchipade/Documents/GitHub/Apple-ContentLake/springboot-SQS-Impl/src/main/resources/data/internal-425-Test-1-US.json"));
        TestIngestionService s = new TestIngestionService();
        java.lang.reflect.Method m = DataIngestionService.class.getDeclaredMethod("extractItemsFromRaw", String.class,
                String.class);
        m.setAccessible(true);
        List<Map<String, Object>> maps = (List<Map<String, Object>>) m.invoke(s, json, "test");

        long imageCount = maps.stream().filter(map -> {
            String role = (String) map.get("itemType");
            String originalFieldName = (String) map.get("originalFieldName");
            Object context = map.get("context");
            String contextStr = context != null ? context.toString() : "";
            return "image".equalsIgnoreCase(role) || "picture".equalsIgnoreCase(role) ||
                    (originalFieldName != null && originalFieldName.contains("url"));
        }).count();

        Files.writeString(Paths.get("target/count_result.txt"), "======> EXACT_IMAGE_COUNT_IN_ONE_FILE=" + imageCount);
    }
}

class TestIngestionService extends DataIngestionService {
    public TestIngestionService() {
        super(null, null, null, null, new ObjectMapper(), null, null, null, null, null, null, null, null, null);
    }
}
