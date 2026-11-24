package edu.atlas.pwaudit;

import edu.atlas.pwaudit.index.PasswordIndex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end API tests against the real 1M-password dataset in ../data. */
@SpringBootTest
@AutoConfigureMockMvc
class PwauditApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void auditsLeakedPassword() throws Exception {
        mvc.perform(post("/api/audit").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breachStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.score").value(0));
    }

    @Test
    void auditsStrongPassword() throws Exception {
        mvc.perform(post("/api/audit").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"tK9#vQ2!mZ8$pL4x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breachStatus").value("NOT_PRESENT"))
                .andExpect(jsonPath("$.diskProbes").value(0));
    }

    @Test
    void rejectsEmptyAndMalformedBodies() throws Exception {
        mvc.perform(post("/api/audit").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_input"));
        mvc.perform(post("/api/audit").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchUploadAndMissingFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pw.txt", "text/plain", "123456\nqwerty\n".getBytes());
        mvc.perform(multipart("/api/audit/batch").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedBreached").value(2));
        mvc.perform(multipart("/api/audit/batch")).andExpect(status().isBadRequest());
    }

    @Test
    void simulatesTheDemoOrganisation() throws Exception {
        MockMultipartFile users = new MockMultipartFile("users", "users.csv", "text/csv",
                Files.readAllBytes(Path.of("../data/org-demo/users.csv")));
        MockMultipartFile access = new MockMultipartFile("access", "access.csv", "text/csv",
                Files.readAllBytes(Path.of("../data/org-demo/access.csv")));
        mvc.perform(multipart("/api/org/simulate").file(users).file(access)
                        .param("attacker", "online").param("budgetSeconds", "86400")
                        .param("target", "Payroll DB").param("terms", "kestrel, pune"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").value(240))
                .andExpect(jsonPath("$.cut.target").value("Payroll DB"))
                .andExpect(jsonPath("$.cut.accountsToFix").isNumber());
        mvc.perform(get("/api/org/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value("Payroll DB"));
    }

    @Test
    void simulationRejectsBadParameters() throws Exception {
        MockMultipartFile users = new MockMultipartFile("users", "users.csv", "text/csv", "username,password\na,123456\n".getBytes());
        mvc.perform(multipart("/api/org/simulate").file(users).param("attacker", "quantum"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_input"));
        mvc.perform(multipart("/api/org/simulate").file(users).param("budgetSeconds", "soon"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/org/simulate")).andExpect(status().isBadRequest());
    }

    @Test
    void redactsTheSampleChatExport() throws Exception {
        String sample = Files.readString(Path.of("../data/redact-sample.txt"));
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("text", sample));
        mvc.perform(post("/api/redact").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byKind.PASSWORD").value(4))
                .andExpect(jsonPath("$.byKind.LEAKED-PASSWORD").value(2))
                .andExpect(jsonPath("$.byKind.PIN").value(1))
                .andExpect(jsonPath("$.byKind.OTP").value(1))
                .andExpect(jsonPath("$.redacted").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Kestrel@2026"))));
        mvc.perform(post("/api/redact").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/redact/sample")).andExpect(status().isOk());
    }

    @Autowired
    PasswordIndex index;

    @Test
    void noFalseNegativesAcrossTheWholeLeakedList() throws Exception {
        List<String> leaked = Files.readAllLines(Path.of("../data/leaked-1M.txt")).stream().filter(s -> !s.isEmpty()).toList();
        long misses = leaked.stream().filter(p -> !index.bloom().mightContain(PasswordIndex.sha1(p))).count();
        assertEquals(0, misses, "Bloom filter must never miss a leaked password");
        for (int i = 0; i < leaked.size(); i += 997) {
            assertTrue(index.store().lookup(PasswordIndex.sha1(leaked.get(i))).found(), leaked.get(i));
        }
    }

    @Test
    void statsDescribeLoadedIndex() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloomHashes").value(7));
    }
}
