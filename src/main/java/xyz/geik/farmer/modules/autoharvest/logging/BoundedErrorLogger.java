package xyz.geik.farmer.modules.autoharvest.logging;

import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.configuration.LoggingSettings;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Writes failures away from region threads and rotates a strictly bounded set
 * of files. The queue is bounded so an exception storm cannot consume memory.
 */
public final class BoundedErrorLogger implements ModuleDiagnostics, AutoCloseable {

    private static final int QUEUE_CAPACITY = 256;
    private static final int MAX_ENTRY_CHARACTERS = 131_072;

    private final Path errorFile;
    private final Logger console;
    private final ArrayBlockingQueue<String> entries = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean writeFailureReported = new AtomicBoolean();
    private final AtomicLong droppedEntries = new AtomicLong();
    private final Thread writerThread;

    private volatile boolean debugEnabled;
    private volatile long maximumBytes;
    private volatile int historyFiles;

    public BoundedErrorLogger(
            @NotNull Path moduleDirectory,
            @NotNull Logger console,
            @NotNull LoggingSettings settings
    ) {
        this(moduleDirectory.resolve("error.log"), console, settings.debugEnabled(),
                settings.errorMaxSizeBytes(), settings.errorHistoryFiles());
    }

    BoundedErrorLogger(
            Path errorFile,
            Logger console,
            boolean debugEnabled,
            long maximumBytes,
            int historyFiles
    ) {
        this.errorFile = Objects.requireNonNull(errorFile, "errorFile").toAbsolutePath().normalize();
        this.console = Objects.requireNonNull(console, "console");
        configure(debugEnabled, maximumBytes, historyFiles);
        writerThread = new Thread(this::writeLoop, "Farmer-AutoHarvest-error-log");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public void configure(@NotNull LoggingSettings settings) {
        configure(settings.debugEnabled(), settings.errorMaxSizeBytes(), settings.errorHistoryFiles());
    }

    void configure(boolean debugEnabled, long maximumBytes, int historyFiles) {
        this.debugEnabled = debugEnabled;
        this.maximumBytes = Math.max(1_024L, maximumBytes);
        this.historyFiles = Math.max(0, historyFiles);
    }

    @Override
    public void debug(@NotNull String message) {
        if (debugEnabled) {
            console.info(message);
        }
    }

    @Override
    public void error(@NotNull String message, Throwable exception) {
        if (!running.get()) {
            return;
        }
        if (entries.remainingCapacity() == 0) {
            droppedEntries.incrementAndGet();
            return;
        }
        String entry = format(message, exception);
        if (!entries.offer(entry)) {
            droppedEntries.incrementAndGet();
        }
    }

    private String format(String message, Throwable exception) {
        StringBuilder output = new StringBuilder(512)
                .append('[').append(Instant.now()).append("] [")
                .append(Thread.currentThread().getName()).append("] ")
                .append(message).append(System.lineSeparator());
        if (exception != null) {
            StringWriter stack = new StringWriter();
            exception.printStackTrace(new PrintWriter(stack));
            output.append(stack);
        }
        if (output.length() > MAX_ENTRY_CHARACTERS) {
            output.setLength(MAX_ENTRY_CHARACTERS);
            output.append(System.lineSeparator()).append("[stack trace truncated]");
        }
        output.append(System.lineSeparator());
        return output.toString();
    }

    private void writeLoop() {
        while (running.get() || !entries.isEmpty()) {
            try {
                String entry = entries.poll(250L, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    write(entry);
                    writeDroppedEntryCount();
                }
            }
            catch (InterruptedException exception) {
                if (running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            catch (IOException | RuntimeException exception) {
                reportWriteFailure(exception);
            }
        }
    }

    private void writeDroppedEntryCount() throws IOException {
        long dropped = droppedEntries.getAndSet(0L);
        if (dropped > 0L) {
            write(format("AutoHarvest dropped " + dropped
                    + " burst error entries because its bounded writer queue was full.", null));
        }
    }

    private void write(String entry) throws IOException {
        byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(errorFile.getParent());
        long currentSize = Files.exists(errorFile) ? Files.size(errorFile) : 0L;
        if (currentSize > 0L && currentSize + bytes.length > maximumBytes) {
            rotate();
        }
        if (bytes.length > maximumBytes) {
            byte[] bounded = new byte[(int) maximumBytes];
            System.arraycopy(bytes, 0, bounded, 0, bounded.length);
            bytes = bounded;
        }
        Files.write(errorFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void rotate() throws IOException {
        if (historyFiles <= 0) {
            Files.deleteIfExists(errorFile);
            return;
        }
        Files.deleteIfExists(historyPath(historyFiles));
        for (int index = historyFiles - 1; index >= 1; index--) {
            Path source = historyPath(index);
            if (Files.exists(source)) {
                Files.move(source, historyPath(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.exists(errorFile)) {
            Files.move(errorFile, historyPath(1), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path historyPath(int index) {
        return errorFile.resolveSibling(errorFile.getFileName() + "." + index);
    }

    private void reportWriteFailure(Throwable exception) {
        if (writeFailureReported.compareAndSet(false, true)) {
            console.warning("AutoHarvest could not write its bounded error.log: " + exception.getMessage());
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        writerThread.interrupt();
        try {
            writerThread.join(2_000L);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
