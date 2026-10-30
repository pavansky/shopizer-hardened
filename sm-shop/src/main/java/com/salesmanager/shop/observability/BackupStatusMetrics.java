package com.salesmanager.shop.observability;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes the result of the last good database backup, read from the status file that
 * deploy/backup/backup.sh writes after a dump has passed its checks (mounted read-only
 * into the API container). The file is read on each scrape; it is a few bytes.
 *
 *   shopizer_backup_last_success_timestamp_seconds  epoch of the last good dump, 0 if none
 *   shopizer_backup_size_bytes                      compressed size of that dump
 *   shopizer_backup_tables                          tables in that dump
 *
 * A missing or unreadable file reads as 0, which the BackupStale alert treats as stale.
 */
@Component
public class BackupStatusMetrics {

	private final Path statusFile;

	public BackupStatusMetrics(MeterRegistry registry,
			@Value("${shopizer.backup.status-file:/backup-status/backup.status}") String statusFile) {
		this.statusFile = Paths.get(statusFile);
		Gauge.builder("shopizer.backup.last.success.timestamp.seconds", this, m -> m.read("last_success_epoch"))
				.description("Unix time of the last backup that passed validation, 0 if none").register(registry);
		Gauge.builder("shopizer.backup.size.bytes", this, m -> m.read("size_bytes"))
				.description("Compressed size of the last good backup").register(registry);
		Gauge.builder("shopizer.backup.tables", this, m -> m.read("table_count"))
				.description("Tables in the last good backup").register(registry);
	}

	double read(String key) {
		if (!Files.isReadable(statusFile)) {
			return 0;
		}
		Properties status = new Properties();
		try (Reader reader = Files.newBufferedReader(statusFile, StandardCharsets.UTF_8)) {
			status.load(reader);
			return Double.parseDouble(status.getProperty(key, "0").trim());
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}
}
