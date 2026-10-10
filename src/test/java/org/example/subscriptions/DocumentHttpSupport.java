package org.example.subscriptions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class DocumentHttpSupport {
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    DocumentHttpSupport(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    record HttpSession(MockHttpSession session, Cookie csrf, String token, String header) {
    }

    HttpSession login(String user) throws Exception {
        var logged = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("username",
                user, "password", user, "remember", false)))).andReturn();
        assertThat(logged.getResponse().getStatus()).isEqualTo(200);
        var session = (MockHttpSession) logged.getRequest().getSession(false);
        var csrf = mockMvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        var token = objectMapper.readTree(csrf.getResponse().getContentAsString());
        return new HttpSession(session, csrf.getResponse().getCookie("XSRF-TOKEN"), token.path("token").asText(),
                token.path("headerName").asText());
    }

    MvcResult request(HttpSession who, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.session(who.session()).cookie(who.csrf()).header(who.header(), who.token());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(request).andReturn();
    }

    Map<String, Object> object(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<Map<String,
                Object>>() {
        });
    }
}
