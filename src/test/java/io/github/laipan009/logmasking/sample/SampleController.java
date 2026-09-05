package io.github.laipan009.logmasking.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logs ordinary application messages without explicit masking: argument dumps, external response
 * bodies and raw error payloads. The starter only transforms the rendered output.
 */
@RestController
public class SampleController {

    private static final Logger LOG = LoggerFactory.getLogger(SampleController.class);

    @PostMapping("/v1/profiles/{id}/update")
    public CustomerResponse update(@PathVariable String id,
                                   @RequestParam String actor,
                                   @RequestBody ProfileUpdateRequest request) {
        var customer = new CustomerResponse(id, "KANAREYKA-FIO-8842", "123-456-789 01");

        // 1. Argument dump, as emitted by an application logging aspect.
        LOG.info("[SampleController]: Start execution method - update(), with params: {}",
                java.util.Map.of("id", id, "actor", actor, "request", request));
        // 2. Query string, as emitted by an HTTP client logger.
        LOG.info("→ GET /customers/{}?actor={}&page=0", id, actor);
        // 3. Raw external response body.
        LOG.info("← Body: {\"customerId\":\"{}\",\"fullName\":\"{}\",\"snils\":\"{}\"}",
                id, customer.fullName(), customer.snils());
        // 4. Complete HTTP headers.
        LOG.info("Headers: [Authorization=Bearer canary-secret-token, Content-Type=application/json]");
        // 5. Raw payload in an error message.
        LOG.error("When trying to deserialize message: {} There was a error: ",
                "{\"requesterAlias\":\"" + request.requesterAlias() + "\"}");

        return customer;
    }
}
