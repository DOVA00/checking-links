package com.example;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api/advanced")
@CrossOrigin(origins = "*")
public class AdvancedCheckerController {

    // Внедряем основной контроллер для проверки URL
    private final CheckerController checkerController;

    public AdvancedCheckerController(CheckerController checkerController) {
        this.checkerController = checkerController;
    }

    @PostMapping("/check-file")
    public ResponseEntity<?> checkFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Файл пустой")
                );
            }

            if (file.getSize() > 10 * 1024 * 1024) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Файл слишком большой (макс. 10MB)")
                );
            }

            // Определяем тип файла по расширению
            String fileName = file.getOriginalFilename().toLowerCase();
            String content;

            if (fileName.endsWith(".txt") || fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                // Текстовые файлы читаем напрямую
                content = new String(file.getBytes(), "UTF-8");

            } else if (fileName.endsWith(".docx")) {
                // Word 2007+ (.docx) - читаем как ZIP
                content = extractTextFromDocx(file.getInputStream());

            } else if (fileName.endsWith(".pdf")) {
                // PDF файлы - упрощенная обработка
                content = extractTextFromPDF(file.getInputStream());

            } else if (fileName.endsWith(".doc")) {
                // Старый формат Word (.doc) - сложно без библиотек
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Формат .doc не поддерживается. Используйте .docx или .txt")
                );

            } else {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Неподдерживаемый формат файла")
                );
            }

            // Извлекаем ссылки из текста
            Set<String> urls = extractUrlsFromText(content);

            if (urls.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "message", "В файле не найдено ссылок",
                        "fileName", file.getOriginalFilename(),
                        "fileSize", formatFileSize(file.getSize()),
                        "totalUrlsFound", 0,
                        "checkedUrls", 0,
                        "results", List.of()
                ));
            }

            // Проверяем найденные ссылки
            List<Map<String, Object>> results = new ArrayList<>();
            int checkedCount = 0;

            for (String url : urls) {
                if (checkedCount >= 50) { // Ограничиваем количество проверок
                    break;
                }

                try {
                    ResponseEntity<?> response = checkerController.check(url);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        Object body = response.getBody();
                        if (body instanceof CheckerController.SiteResult) {
                            CheckerController.SiteResult siteResult = (CheckerController.SiteResult) body;
                            results.add(Map.of(
                                    "url", url,
                                    "safeBrowsing", siteResult.safeBrowsing(),
                                    "score", siteResult.score(),
                                    "level", siteResult.level(),
                                    "https", siteResult.https(),
                                    "validSSL", siteResult.validSSL()
                            ));
                            checkedCount++;
                        }
                    }
                } catch (Exception e) {
                    // Пропускаем проблемные ссылки
                }
            }

            return ResponseEntity.ok(Map.of(
                    "fileName", file.getOriginalFilename(),
                    "fileSize", formatFileSize(file.getSize()),
                    "totalUrlsFound", urls.size(),
                    "checkedUrls", checkedCount,
                    "results", results,
                    "processingTime", Instant.now().toString()
            ));

        } catch (IOException e) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Ошибка чтения файла: " + e.getMessage())
            );
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Внутренняя ошибка: " + e.getMessage())
            );
        }
    }

    // Извлечение текста из DOCX (Word 2007+)
    private String extractTextFromDocx(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();

        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                // Ищем файлы document.xml в архиве
                if (entry.getName().equals("word/document.xml") ||
                        entry.getName().endsWith(".xml")) {

                    // Читаем XML как текст
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;

                    while ((len = zipInputStream.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }

                    String xmlContent = baos.toString("UTF-8");
                    // Извлекаем текст из XML (упрощенно)
                    text.append(extractTextFromXML(xmlContent));
                }
                zipInputStream.closeEntry();
            }
        }

        return text.toString();
    }

    // Упрощенное извлечение текста из XML
    private String extractTextFromXML(String xml) {
        // Удаляем XML теги, оставляем текст между ними
        return xml.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // Упрощенное извлечение текста из PDF (только для текстовых PDF)
    private String extractTextFromPDF(InputStream inputStream) throws IOException {
        // Простая реализация для текстовых PDF
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder text = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            // Ищем текст в PDF (это очень упрощенно)
            if (line.contains("http://") || line.contains("https://") ||
                    line.matches(".*[a-zA-Z0-9]\\.[a-zA-Z]{2,}.*")) {
                text.append(line).append("\n");
            }
        }

        return text.toString();
    }

    // Извлечение URL из текста
    private Set<String> extractUrlsFromText(String text) {
        Set<String> urls = new LinkedHashSet<>();

        if (text == null || text.trim().isEmpty()) {
            return urls;
        }

        // Регулярное выражение для URL
        String urlRegex = "\\b(?:https?|ftp)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";
        Pattern urlPattern = Pattern.compile(urlRegex, Pattern.CASE_INSENSITIVE);
        Matcher urlMatcher = urlPattern.matcher(text);

        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            // Нормализуем URL
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ftp://")) {
                url = "https://" + url;
            }
            urls.add(url);
        }

        // Также ищем домены без протокола
        String domainRegex = "\\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}\\b";
        Pattern domainPattern = Pattern.compile(domainRegex, Pattern.CASE_INSENSITIVE);
        Matcher domainMatcher = domainPattern.matcher(text);

        while (domainMatcher.find()) {
            String domain = domainMatcher.group();
            // Исключаем email адреса
            if (!domain.contains("@") &&
                    !domain.startsWith("localhost") &&
                    !domain.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {

                // Проверяем, не является ли это частью уже найденного URL
                boolean isPartOfUrl = false;
                for (String url : urls) {
                    if (url.contains(domain)) {
                        isPartOfUrl = true;
                        break;
                    }
                }

                if (!isPartOfUrl) {
                    urls.add("https://" + domain);
                }
            }
        }

        return urls;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @PostMapping("/check-text")
    public ResponseEntity<?> checkText(@RequestBody Map<String, String> request) {
        try {
            String text = request.get("text");

            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Текст не может быть пустым")
                );
            }

            Set<String> urls = extractUrlsFromText(text);

            if (urls.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "message", "В тексте не найдено ссылок",
                        "totalUrlsFound", 0,
                        "checkedUrls", 0,
                        "results", List.of()
                ));
            }

            List<Map<String, Object>> results = new ArrayList<>();
            int checkedCount = 0;

            for (String url : urls) {
                if (checkedCount >= 50) {
                    break;
                }

                try {
                    ResponseEntity<?> response = checkerController.check(url);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        Object body = response.getBody();
                        if (body instanceof CheckerController.SiteResult) {
                            CheckerController.SiteResult siteResult = (CheckerController.SiteResult) body;
                            results.add(Map.of(
                                    "url", url,
                                    "safeBrowsing", siteResult.safeBrowsing(),
                                    "score", siteResult.score(),
                                    "level", siteResult.level()
                            ));
                            checkedCount++;
                        }
                    }
                } catch (Exception e) {
                    // Пропускаем проблемные ссылки
                }
            }

            return ResponseEntity.ok(Map.of(
                    "totalUrlsFound", urls.size(),
                    "checkedUrls", checkedCount,
                    "results", results
            ));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Ошибка обработки текста: " + e.getMessage())
            );
        }
    }
}