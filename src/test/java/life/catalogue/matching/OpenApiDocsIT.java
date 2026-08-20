/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package life.catalogue.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the actual web context and asserts the generated OpenAPI document is servable and sane.
 *
 * <p>The rest of the suite never starts a Spring context, which is why two breakages introduced by
 * the Spring Boot 3 / springdoc 2 upgrade shipped past a green build: /v3/api-docs threw
 * NoClassDefFoundError on the jakarta.xml.bind namespace, and the v1 endpoint documented a bogus
 * synthetic "arg13" parameter for its unannotated ClassificationQuery argument.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = OpenApiDocsIT.TestApp.class,
    properties = {
      "spring.cloud.zookeeper.enabled=false",
      "spring.cloud.zookeeper.discovery.enabled=false",
      "spring.cloud.zookeeper.config.enabled=false",
      "spring.cloud.service-registry.auto-registration.enabled=false",
      "spring.boot.admin.client.enabled=false",
      "spring.main.banner-mode=off"
    })
public class OpenApiDocsIT {

  /** Minimal app: the real controllers and springdoc, over the in-memory test index. */
  @SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
  @Import(MatchingTestConfiguration.class)
  static class TestApp {}

  private static final Pattern SYNTHETIC_PARAM = Pattern.compile("^arg\\d+$");

  @Autowired TestRestTemplate rest;

  private JsonNode apiDocs() throws Exception {
    ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
    assertEquals(
        HttpStatus.OK,
        res.getStatusCode(),
        "/v3/api-docs must render - body was: " + res.getBody());
    return new ObjectMapper().readTree(res.getBody());
  }

  @Test
  public void apiDocsAreServable() throws Exception {
    JsonNode doc = apiDocs();
    assertTrue(doc.has("paths"), "no paths in the OpenAPI document");
    assertTrue(doc.get("paths").has("/v1/species/match"), "v1 match is not documented");
    assertTrue(doc.get("paths").has("/v2/species/match"), "v2 match is not documented");
  }

  /**
   * springdoc falls back to "argN" when it cannot name a handler argument - a command object with
   * no {@code @ParameterObject} on it. Swagger UI then renders it as a required object-typed query
   * parameter that nobody can fill in, which breaks "Try it out" for the whole operation.
   */
  @Test
  public void noSyntheticParameterNames() throws Exception {
    JsonNode paths = apiDocs().get("paths");
    List<String> offenders = new ArrayList<>();
    paths
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        op -> {
                          JsonNode params = op.getValue().get("parameters");
                          if (params == null) return;
                          for (JsonNode p : params) {
                            String name = p.path("name").asText("");
                            if (SYNTHETIC_PARAM.matcher(name).matches()) {
                              offenders.add(path.getKey() + " " + op.getKey() + " -> " + name);
                            }
                          }
                        }));
    assertTrue(offenders.isEmpty(), "springdoc could not name these parameters: " + offenders);
  }

  @Test
  public void v1MatchServesAV1ShapedResponse() {
    ResponseEntity<String> res =
        rest.getForEntity("/v1/species/match?name=Inachis+io", String.class);
    assertEquals(HttpStatus.OK, res.getStatusCode());
    String body = res.getBody();
    assertTrue(body != null && body.contains("\"matchType\""), "not a v1 payload: " + body);
    assertTrue(body.contains("\"usageKey\""), "v1 payload has no usageKey: " + body);
  }
}
