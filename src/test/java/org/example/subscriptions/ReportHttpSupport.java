package org.example.subscriptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ReportHttpSupport {
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    ReportHttpSupport(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    MockHttpSession login() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"admin\",\"remember\":false}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    JsonNode getJson(MockHttpSession session, String path, Map<String, String> params) throws Exception {
        var request = get(path).session(session);
        params.forEach(request::param);
        var result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    org.springframework.test.web.servlet.MvcResult report(MockHttpSession session, String from, String to,
            String clientId) throws Exception {
        var csrf = mockMvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        var token = objectMapper.readTree(csrf.getResponse().getContentAsString());
        return mockMvc.perform(post("/api/divkit/page-action").param("route", "/revenue-report").param("key",
                "generate").session(session).cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(token.path("headerName")
                .asText(), token.path("token").asText()).contentType("application/json").content(objectMapper.writeValueAsString(Map.of("inputs",
                Map.of("from", from, "to", to, "client", clientId))))).andReturn();
    }

    JsonNode reportJson(MockHttpSession session, String from, String to, String clientId) throws Exception {
        var result = report(session, from, to, clientId);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("feedback");
    }
}
