package com.salesmanager.shop.store.api.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLTransientConnectionException;

import org.hibernate.exception.JDBCConnectionException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.salesmanager.core.business.exception.ServiceException;

/**
 * A request that could not get a pooled connection within db.connectionTimeout answers
 * 503 with Retry-After, however deeply the Hikari exception is wrapped. Other errors keep
 * their status.
 */
public class RestErrorHandlerPoolTimeoutTest {

	private MockMvc mvc;

	@Before
	public void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new FailingController())
				.setControllerAdvice(new RestErrorHandler()).build();
	}

	@Test
	public void poolTimeoutAtTransactionStartIs503() throws Exception {
		mvc.perform(get("/test/pool-timeout/transaction"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", RestErrorHandler.POOL_TIMEOUT_RETRY_AFTER_SECONDS))
				.andExpect(jsonPath("$.errorCode").value("503"));
	}

	@Test
	public void poolTimeoutWrappedByAServiceIs503() throws Exception {
		mvc.perform(get("/test/pool-timeout/service"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", RestErrorHandler.POOL_TIMEOUT_RETRY_AFTER_SECONDS));
	}

	@Test
	public void otherErrorsKeepTheirStatus() throws Exception {
		mvc.perform(get("/test/other")).andExpect(status().isInternalServerError())
				.andExpect(header().doesNotExist("Retry-After"));
		mvc.perform(get("/test/service-other")).andExpect(status().isBadRequest())
				.andExpect(header().doesNotExist("Retry-After"));
	}

	private static SQLTransientConnectionException hikariTimeout() {
		return new SQLTransientConnectionException(
				"HikariPool-1 - Connection is not available, request timed out after 2000ms.");
	}

	@RestController
	static class FailingController {

		@GetMapping("/test/pool-timeout/transaction")
		public String transaction() {
			throw new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
					new JDBCConnectionException("Unable to acquire JDBC Connection", hikariTimeout()));
		}

		@GetMapping("/test/pool-timeout/service")
		public String service() {
			throw new ServiceRuntimeException("Cannot list products",
					new ServiceException(new JDBCConnectionException("Unable to acquire JDBC Connection",
							hikariTimeout())));
		}

		@GetMapping("/test/other")
		public String other() {
			throw new IllegalStateException("broken");
		}

		@GetMapping("/test/service-other")
		public String serviceOther() {
			throw new ServiceRuntimeException("bad input");
		}
	}
}
