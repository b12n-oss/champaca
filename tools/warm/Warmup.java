// Copyright (C) 2026  champaca contributors
// Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
//   Anthony Green <green@moxielogic.com>
//
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See COPYING and COPYING.CLASSPATH-EXCEPTION.

// A training run for the startup cache (jolt warm): touches the JDK paths a
// program commonly reaches early, so their compiled code is in the cache
// before any program needs it. Nothing here writes outside a temporary file
// it deletes, and it prints one line.

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Warmup {
    public static void main(String[] args) throws Exception {
        int n = 0;
        n += random();
        n += time();
        n += text();
        n += collections();
        n += numbers();
        n += io();
        n += concurrency();
        System.out.println("warm: " + n + " checks");
    }

    static int random() throws Exception {
        SecureRandom sr = new SecureRandom();
        byte[] b = new byte[16];
        sr.nextBytes(b);
        UUID.randomUUID().toString();
        MessageDigest.getInstance("SHA-256").digest(b);
        Base64.getEncoder().encodeToString(b);
        Base64.getDecoder().decode(Base64.getEncoder().encode(b));
        return 1;
    }

    static int time() {
        ZoneId z = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(z);
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now);
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(now);
        LocalDate.parse("2026-01-02").plusDays(30).getDayOfWeek();
        Duration.between(Instant.now(), Instant.now().plusSeconds(90)).toString();
        return 1;
    }

    static int text() {
        String.format("%s %d %.2f %x %08.3f %,d", "a", 42, 3.14159, 255, 2.5, 1234567);
        String.format(Locale.ROOT, "%-10s|%5d", "x", 7);
        NumberFormat.getInstance().format(1234567.891);
        Pattern p = Pattern.compile("(\\w+)@(\\w+)\\.com");
        Matcher m = p.matcher("one alice@example.com two bob@test.com");
        while (m.find()) m.group(1);
        "a,b,,c".split(",");
        String.join("-", List.of("x", "y", "z"));
        "Hello World".toLowerCase().toUpperCase().replace('O', '0').strip().repeat(2);
        new String("héllo wörld".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        "héllo".getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append(i).append(',');
        sb.reverse().toString();
        URI.create("https://example.com/a/b?q=1#f").resolve("../c").toString();
        return 1;
    }

    static int collections() {
        List<String> words = new ArrayList<>(Arrays.asList("pear", "fig", "apple", "banana", "kiwi"));
        words.sort(null);
        words.sort((a, b) -> Integer.compare(a.length(), b.length()));
        Map<Integer, List<String>> byLen = words.stream().collect(Collectors.groupingBy(String::length));
        new TreeMap<>(byLen).toString();
        Map<String, Integer> counts = new HashMap<>();
        for (String w : words) counts.merge(w.substring(0, 1), 1, Integer::sum);
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", ", "[", "]"));
        IntStream.range(0, 100).filter(i -> i % 3 == 0).map(i -> i * i).sum();
        IntStream.rangeClosed(1, 10).boxed().collect(Collectors.toList()).toString();
        Optional.ofNullable(counts.get("z")).map(Object::toString).orElse("none");
        words.removeIf(w -> w.startsWith("k"));
        Properties props = new Properties();
        props.setProperty("k", "v");
        props.getProperty("k");
        return 1;
    }

    static int numbers() {
        new BigDecimal("12345.6789").multiply(new BigDecimal("1.1")).setScale(2, RoundingMode.HALF_UP).toPlainString();
        BigInteger.valueOf(2).pow(200).mod(BigInteger.valueOf(1_000_000_007L)).toString();
        Double.parseDouble("3.25e10");
        Double.toString(Math.PI);
        Long.parseLong("-9223372036854775808");
        Integer.toHexString(Integer.reverse(12345));
        return 1;
    }

    static int io() throws IOException {
        Path f = Files.createTempFile("champaca-warm", ".txt");
        try {
            Files.writeString(f, "line one\nline two\n");
            Files.readAllLines(f).size();
            try (var lines = Files.lines(f)) {
                lines.filter(l -> l.contains("two")).count();
            }
            Files.size(f);
        } finally {
            Files.deleteIfExists(f);
        }
        return 1;
    }

    static int concurrency() throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            ex.submit(() -> 21 * 2).get();
            CompletableFuture.supplyAsync(() -> "a", ex).thenApply(s -> s + "b").get();
        } finally {
            ex.shutdown();
        }
        ConcurrentHashMap<String, Integer> chm = new ConcurrentHashMap<>();
        chm.computeIfAbsent("k", k -> 1);
        Thread t = new Thread(() -> chm.put("t", 2));
        t.start();
        t.join();
        return 1;
    }
}
