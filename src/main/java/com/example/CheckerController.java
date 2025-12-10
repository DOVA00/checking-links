package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.net.ssl.HttpsURLConnection;
import java.security.cert.X509Certificate;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CheckerController {

    @Value("${app.safe-browsing.api-key:}")
    private String safeBrowsingApiKey;

    private final Map<String, SiteResult> cache = new ConcurrentHashMap<>();
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
    );
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^((?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+[A-Za-z]{2,6}$"
    );

    @GetMapping("/check")
    public ResponseEntity<?> check(@RequestParam String link) {
        try {
           
            if (link == null || link.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                        new ErrorResponse("Пожалуйста, укажите ссылку для проверки")
                );
            }

            String normalizedLink = normalizeUrl(link);

            if (!isValidUrl(normalizedLink)) {
                return ResponseEntity.badRequest().body(
                        new ErrorResponse("Некорректный URL формат")
                );
            }

            if (cache.containsKey(normalizedLink)) {
                SiteResult cached = cache.get(normalizedLink);
                if (Duration.between(cached.timestamp(), Instant.now()).toHours() < 24) {
                    return ResponseEntity.ok(cached);
                }
            }

            Map<String, Object> checks = performAllChecksSequentially(normalizedLink);

            double score = calculateTrustScore(checks);
            String level = determineTrustLevel(score);

            SiteResult result = new SiteResult(
                    normalizedLink,
                    (Boolean) checks.get("https"),
                    (Integer) checks.get("ageMonths"),
                    (Boolean) checks.get("hasContact"),
                    (Boolean) checks.get("safeBrowsing"),
                    (Boolean) checks.get("validSSL"),
                    (Boolean) checks.get("validDomain"),
                    (Boolean) checks.get("hasPrivacyPolicy"),
                    score,
                    level,
                    Instant.now(),
                    checks
            );

            cache.put(normalizedLink, result);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse("Ошибка при проверке сайта: " + e.getMessage())
            );
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalChecked", cache.size());
        stats.put("averageScore",
                cache.values().stream()
                        .mapToDouble(SiteResult::score)
                        .average()
                        .orElse(0.0)
        );
        return ResponseEntity.ok(stats);
    }

    @Scheduled(fixedRate = 3600000)
    public void clearOldCache() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        cache.entrySet().removeIf(entry ->
                entry.getValue().timestamp().isBefore(cutoff)
        );
    }

    private Map<String, Object> performAllChecksSequentially(String url) {
        Map<String, Object> results = new HashMap<>();

        results.put("https", checkHttps(url));
        results.put("validSSL", checkSSL(url));
        results.put("validDomain", validateDomain(url));

        results.put("ageMonths", getDomainAgeMonths(url));
        results.put("hasContact", hasContactPageWithRetry(url, 2));
        results.put("hasPrivacyPolicy", hasPrivacyPolicyWithRetry(url, 2));
        results.put("safeBrowsing", checkSafeBrowsing(url));

        return results;
    }

    private boolean checkHttps(String urlString) {
        try {
            URL url = new URL(urlString);
            return "https".equals(url.getProtocol());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkSSL(String urlString) {
        if (!urlString.startsWith("https://")) return false;

        try {
            URL url = new URL(urlString);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();

            X509Certificate cert = (X509Certificate) connection.getServerCertificates()[0];
            boolean valid = new Date().before(cert.getNotAfter());

            connection.disconnect();
            return valid;
        } catch (Exception e) {
            return false;
        }
    }

    private int getDomainAgeMonths(String urlString) {
       
        return -1;
    }

    private boolean hasContactPageWithRetry(String urlString, int retries) {
        String[] contactPaths = {
                "/contact", "/contacts", "/contact-us", "/contactus",
                "/about/contact", "/info/contact", "/feedback"
        };

        for (int attempt = 0; attempt < retries; attempt++) {
            for (String path : contactPaths) {
                if (checkUrlExists(urlString + path)) {
                    return true;
                }
            }
   
            if (attempt < retries - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return false;
    }

    private boolean hasPrivacyPolicyWithRetry(String urlString, int retries) {
        String[] privacyPaths = {
                "/privacy", "/privacy-policy", "/privacypolicy",
                "/privacy_policy", "/policy", "/legal/privacy"
        };

        for (int attempt = 0; attempt < retries; attempt++) {
            for (String path : privacyPaths) {
                if (checkUrlExists(urlString + path)) {
                    return true;
                }
            }
        
            if (attempt < retries - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return false;
    }

    private boolean checkUrlExists(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            con.setRequestMethod("HEAD");
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");

            int code = con.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateDomain(String urlString) {
        try {
            URL url = new URL(urlString);
            String domain = url.getHost();
            return DOMAIN_PATTERN.matcher(domain).matches();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkSafeBrowsing(String urlToCheck) {
      
        if (safeBrowsingApiKey == null || safeBrowsingApiKey.isEmpty() ||
                safeBrowsingApiKey.startsWith("${")) {
            return true;
        }

        try {
            URL url = new URL("https://safebrowsing.googleapis.com/v4/threatMatches:find?key=" + safeBrowsingApiKey);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            JSONObject requestJson = new JSONObject();
            requestJson.put("client", new JSONObject()
                    .put("clientId", "SiteChecker")
                    .put("clientVersion", "1.0.0"));

            requestJson.put("threatInfo", new JSONObject()
                    .put("threatTypes", new JSONArray()
                            .put("MALWARE")
                            .put("SOCIAL_ENGINEERING")
                            .put("UNWANTED_SOFTWARE"))
                    .put("platformTypes", new JSONArray().put("ANY_PLATFORM"))
                    .put("threatEntryTypes", new JSONArray().put("URL"))
                    .put("threatEntries", new JSONArray()
                            .put(new JSONObject().put("url", urlToCheck))));

            try (OutputStream os = con.getOutputStream()) {
                os.write(requestJson.toString().getBytes());
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }

            JSONObject jsonResponse = new JSONObject(response.toString());
            return !jsonResponse.has("matches");

        } catch (Exception e) {
      
            return true;
        }
    }

    private double calculateTrustScore(Map<String, Object> checks) {
        double score = 0;

        if ((Boolean) checks.getOrDefault("https", false)) score += 20;
        if ((Boolean) checks.getOrDefault("validSSL", false)) score += 20;
        if ((Boolean) checks.getOrDefault("safeBrowsing", true)) score += 25;
        if ((Boolean) checks.getOrDefault("validDomain", false)) score += 10;
        if ((Boolean) checks.getOrDefault("hasContact", false)) score += 10;
        if ((Boolean) checks.getOrDefault("hasPrivacyPolicy", false)) score += 10;

        int age = (Integer) checks.getOrDefault("ageMonths", -1);
        if (age >= 60) score += 10;
        else if (age >= 24) score += 8;
        else if (age >= 12) score += 5;

        return Math.min(100, Math.max(0, score));
    }

    private String determineTrustLevel(double score) {
        if (score >= 85) return "ОЧЕНЬ ВЫСОКИЙ";
        if (score >= 70) return "ВЫСОКИЙ";
        if (score >= 50) return "СРЕДНИЙ";
        if (score >= 30) return "НИЗКИЙ";
        return "ОПАСНЫЙ";
    }

    private boolean isValidUrl(String url) {
        try {
            new URL(url);
            return URL_PATTERN.matcher(url).matches();
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeUrl(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public record SiteResult(
            String link,
            boolean https,
            int ageMonths,
            boolean hasContact,
            boolean safeBrowsing,
            boolean validSSL,
            boolean validDomain,
            boolean hasPrivacyPolicy,
            double score,
            String level,
            Instant timestamp,
            Map<String, Object> details
    ) {}

    public record ErrorResponse(String message) {}
}
