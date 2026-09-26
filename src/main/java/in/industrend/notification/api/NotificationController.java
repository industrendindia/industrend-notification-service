package in.industrend.notification.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/notifications")
public class NotificationController {
  public record OtpRequest(String mobile, String otp, String templateKey) {}
  public record Delivery(String provider, String messageId, String status) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final String token;
  private final String configUrl;
  private final String msg91Key;
  private final HttpClient http = HttpClient.newHttpClient();

  public NotificationController(JdbcClient jdbc, ObjectMapper json,
      @Value("${services.internal-token}") String token,
      @Value("${services.config.url}") String configUrl,
      @Value("${app.msg91.auth-key:}") String msg91Key) {
    this.jdbc = jdbc;
    this.json = json;
    this.token = token;
    this.configUrl = configUrl;
    this.msg91Key = msg91Key;
  }

  @PostMapping("/otp")
  Delivery otp(@RequestHeader("X-Service-Token") String supplied, @RequestBody OtpRequest request) {
    if (token.isBlank() || !token.equals(supplied)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    var provider = config("notification.provider", "mock");
    var delivery = "mock".equalsIgnoreCase(provider)
        ? new Delivery("mock", UUID.randomUUID().toString(), "ACCEPTED")
        : sendWhatsAppOtp(request);
    jdbc.sql("insert into industrendindia.notification_deliveries(notification_id,channel,template_key,destination,provider,provider_message_id,status) values(:id,'WHATSAPP',:t,:d,:p,:m,:s)")
        .param("id", UUID.randomUUID()).param("t", request.templateKey()).param("d", request.mobile())
        .param("p", delivery.provider()).param("m", delivery.messageId()).param("s", delivery.status()).update();
    return delivery;
  }

  private Delivery sendWhatsAppOtp(OtpRequest request) {
    var endpoint = config("notification.msg91.whatsapp.endpoint", "https://api.msg91.com/api/v5/whatsapp/whatsapp-outbound-message/bulk/");
    var integratedNumber = config("notification.msg91.whatsapp.integrated_number", "919356419345");
    var templateName = config("notification.msg91.whatsapp.template_name", "indus_otp_auth");
    var language = config("notification.msg91.whatsapp.language", "en");
    var namespace = config("notification.msg91.whatsapp.namespace", "fbdc924e_ff44_4429_aab6_774b16428de0");
    if (msg91Key.isBlank()) throw new IllegalStateException("MSG91_AUTH_KEY is not configured");
    var destination = request.mobile().replaceAll("\\D", "");
    var template = new LinkedHashMap<String,Object>();
    template.put("name", templateName);
    template.put("language", Map.of("code", language, "policy", "deterministic"));
    template.put("namespace", namespace);
    template.put("to_and_components", List.of(Map.of(
        "to", List.of(destination),
        "components", Map.of(
            "body_1", Map.of("type", "text", "value", request.otp()),
            "button_1", Map.of("subtype", "url", "type", "text", "value", request.otp())))));
    var payload = Map.<String,Object>of(
        "integrated_number", integratedNumber,
        "content_type", "template",
        "payload", Map.of("messaging_product", "whatsapp", "type", "template", "template", template));
    try {
      var outbound = HttpRequest.newBuilder(URI.create(endpoint))
          .header("Content-Type", "application/json")
          .header("authkey", msg91Key)
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
      var response = http.send(outbound, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("MSG91 rejected WhatsApp OTP: HTTP " + response.statusCode());
      }
      return new Delivery("msg91-whatsapp", null, "ACCEPTED");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("WhatsApp OTP delivery interrupted", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("WhatsApp OTP delivery failed", exception);
    }
  }

  private String config(String key, String fallback) {
    try {
      var outbound = HttpRequest.newBuilder(URI.create(configUrl + "/internal/v1/config/" + key))
          .header("X-Service-Token", token).GET().build();
      var response = http.send(outbound, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200 ? response.body() : fallback;
    } catch (Exception exception) {
      return fallback;
    }
  }
}
