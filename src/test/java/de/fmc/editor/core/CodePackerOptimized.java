package de.fmc.editor.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

public class CodePackerOptimized {
    public static void main(String[] args) throws IOException {
        // HIER den Pfad zu deinem Projektquellordner eintragen (z.B. "./src")
        Path sourceFolder = Paths.get("./src/main/java");
        Path outputFile = Paths.get("./gesamtes_projekt_kontext.md");

        StringBuilder output = new StringBuilder();
        AtomicInteger fileCount = new AtomicInteger();

        output.append("# Java Quellcode, FXML, CSS & Properties\n");
        output.append("Dieses Dokument enthält alle relevanten Text‑Ressourcen des Projekts.\n\n");

        if (!Files.exists(sourceFolder)) {
            System.out.println("Pfad existiert nicht: " + sourceFolder.toAbsolutePath());
            return;
        }

        // Alle Dateien rekursiv durchgehen
        Files.walk(sourceFolder)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.toString().toLowerCase();
                    // Erweiterte Liste der gewünschten Endungen
                    return name.endsWith(".java") ||
                            name.endsWith(".fxml") ||
                            name.endsWith(".css") ||
                            name.endsWith(".properties");
                })
                .forEach(path -> {
                    try {
                        fileCount.incrementAndGet();
                        String relative = sourceFolder.relativize(path).toString();
                        output.append("## Datei: ").append(relative).append("\n");

                        // Sprache für den Codeblock anhand der Endung ermitteln
                        String lang = getLanguageForFile(path);
                        output.append("```").append(lang).append("\n");
                        output.append(Files.readString(path));
                        output.append("\n```\n\n---\n\n");
                    } catch (IOException e) {
                        System.err.println("Fehler beim Lesen von: " + path);
                    }
                });

        Files.writeString(outputFile, output.toString());

        // Statistik ausgeben
        long bytes = Files.size(outputFile);
        double kilobytes = bytes / 1024.0;
        double megabytes = kilobytes / 1024.0;

        System.out.println("=================================================");
        System.out.println("ERFOLG: Datei wurde generiert!");
        System.out.println("Dateiname: " + outputFile.toAbsolutePath());
        System.out.println("Verarbeitete Dateien: " + fileCount.get());
        System.out.println(String.format("Dateigröße: %.2f KB (%.2f MB)", kilobytes, megabytes));
        System.out.println("=================================================");
    }

    /**
     * Bestimmt die Syntax‑Auszeichnung für den Markdown‑Codeblock.
     */
    private static String getLanguageForFile(Path path) {
        String name = path.toString().toLowerCase();
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".fxml")) return "xml";
        if (name.endsWith(".css")) return "css";
        if (name.endsWith(".properties")) return "properties";
        // Fallback für andere Textdateien
        return "txt";
    }
}