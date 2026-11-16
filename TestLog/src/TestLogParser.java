import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TestLogParser {

    public enum Status {
        PASSED, FAILED, SKIPPED
    }

    /**
     * Для record компилятор Java автоматически генерирует методы equals() и hashCode()
     * на основе всех объявленных полей (name, status, durationMs), поэтому сравнение
     * записей и отсеивание полных дубликатов в Set работает без нашего участия.
     */

    public record TestResult(String name, Status status, long durationMs) {
    }

    public record ParseOutcome(List<TestResult> results, List<String> errors) {
    }


    public ParseOutcome parse(List<String> lines) {

        Set<TestResult> uniqueResults = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();

        for (String rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }

            String line = rawLine.trim();
            String[] parts = line.split("\\|", -1);

            if (parts.length != 3) {
                errors.add(line + " — неверный формат (ожидалось 3 поля через '|', найдено " + parts.length + ")");
                continue;
            }

            String name = parts[0].trim();
            String statusRaw = parts[1].trim();
            String durationRaw = parts[2].trim();

            if (name.isEmpty()) {
                errors.add(line + " — пустое имя теста");
                continue;
            }

            Status status;
            try {
                status = Status.valueOf(statusRaw);
            } catch (IllegalArgumentException e) {
                errors.add(line + " — неизвестный статус: " + statusRaw);
                continue;
            }

            long durationMs;
            try {
                durationMs = Long.parseLong(durationRaw);
            } catch (NumberFormatException e) {
                errors.add(line + " — не число в длительности");
                continue;
            }

            if (durationMs < 0) {
                errors.add(line + " — отрицательная длительность: " + durationMs);
                continue;
            }

            TestResult current = new TestResult(name, status, durationMs);

            // Если такой же результат (имя + статус + время) уже есть,
            // remove() найдёт и удалит его через сгенерированные equals/hashCode записи.
            // Если тест перезапускался с другим статусом или временем, удаляем старую попытку по имени,
            // чтобы в конце осталась только последняя запись.

            uniqueResults.removeIf(existing -> existing.name().equals(name));
            uniqueResults.add(current);
        }

        return new ParseOutcome(new ArrayList<>(uniqueResults), errors);
    }


    public Optional<TestResult> firstFailed(List<TestResult> results) {
        if (results == null) {
            return Optional.empty();
        }
        return results.stream()
                .filter(result -> result.status() == Status.FAILED)
                .findFirst();
    }



    public static void main(String[] args) {
        Path logPath = Path.of("run.log");
        List<String> lines = new ArrayList<>();


        try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            System.err.println("Не удалось прочитать файл " + logPath + ": " + e.getMessage());
            return;
        }

        TestLogParser parser = new TestLogParser();
        TestLogParser.ParseOutcome outcome = parser.parse(lines);

        System.out.println("Результаты:");
        for (TestLogParser.TestResult r : outcome.results()) {
            System.out.printf("  %s — %s (%d ms)%n", r.name(), r.status(), r.durationMs());
        }

        if (outcome.errors().isEmpty()) {
            System.out.println("Битые строки: нет");
        } else {
            System.out.println("Битые строки: " + String.join("; ", outcome.errors()));
        }

        String firstFailedOutput = parser.firstFailed(outcome.results())
                .map(r -> r.name() + " (" + r.durationMs() + " ms)")
                .orElse("Упадений нет");
        System.out.println("Первый упавший: " + firstFailedOutput);
    }

}
