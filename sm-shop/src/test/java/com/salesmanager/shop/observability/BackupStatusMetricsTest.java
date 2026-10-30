package com.salesmanager.shop.observability;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class BackupStatusMetricsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	@Test
	public void readsTheStatusFileWrittenByTheBackupJob() throws Exception {
		Path status = folder.newFile("backup.status").toPath();
		Files.write(status, ("last_success_epoch=1793341200\nsize_bytes=48211\ntable_count=97\n"
				+ "file=SALESMANAGER-20261030T060000Z.sql.gz\n").getBytes(StandardCharsets.UTF_8));
		new BackupStatusMetrics(registry, status.toString());

		assertEquals(1793341200d, gauge("shopizer.backup.last.success.timestamp.seconds"), 0);
		assertEquals(48211d, gauge("shopizer.backup.size.bytes"), 0);
		assertEquals(97d, gauge("shopizer.backup.tables"), 0);
	}

	@Test
	public void missingFileReadsAsZeroSoTheStaleAlertFires() {
		new BackupStatusMetrics(registry, folder.getRoot().toPath().resolve("absent").toString());
		assertEquals(0d, gauge("shopizer.backup.last.success.timestamp.seconds"), 0);
	}

	@Test
	public void unreadableContentReadsAsZero() throws Exception {
		Path status = folder.newFile("backup.status").toPath();
		Files.write(status, "last_success_epoch=not-a-number\n".getBytes(StandardCharsets.UTF_8));
		new BackupStatusMetrics(registry, status.toString());
		assertEquals(0d, gauge("shopizer.backup.last.success.timestamp.seconds"), 0);
	}

	@Test
	public void picksUpANewBackupWithoutRestart() throws Exception {
		Path status = folder.getRoot().toPath().resolve("backup.status");
		new BackupStatusMetrics(registry, status.toString());
		assertEquals(0d, gauge("shopizer.backup.last.success.timestamp.seconds"), 0);
		Files.write(status, "last_success_epoch=1793341200\n".getBytes(StandardCharsets.UTF_8));
		assertEquals(1793341200d, gauge("shopizer.backup.last.success.timestamp.seconds"), 0);
	}

	private double gauge(String name) {
		return registry.get(name).gauge().value();
	}
}
