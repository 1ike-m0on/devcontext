package com.devcontext;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "devcontext.llm.provider=mock",
        "devcontext.llm.gemini.api-key=",
        "devcontext.llm.deepseek.api-key="
})
class DevContextApplicationTests {

    @Test
    void contextLoads() {
    }
}
