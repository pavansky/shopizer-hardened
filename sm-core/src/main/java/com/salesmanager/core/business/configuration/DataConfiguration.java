package com.salesmanager.core.business.configuration;

import java.util.Properties;

import javax.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;


@Configuration
@EnableCaching
public class DataConfiguration {

	/**
	 * Datasource
	 */
    @Value("${db.driverClass}")
    private String driverClassName;
    
    @Value("${db.jdbcUrl}")
    private String url;
    
    @Value("${db.user}")
    private String user;
    
    @Value("${db.password}")
    private String password;

    
    /**
     * Other connection properties
     */
    
    @Value("${hibernate.hbm2ddl.auto}")
    private String hbm2ddl;
    
    @Value("${hibernate.dialect}")
    private String dialect;
    
    @Value("${db.show.sql}")
    private String showSql;
    
    @Value("${db.preferredTestQuery}")
    private String preferredTestQuery;
    
    @Value("${db.schema}")
    private String schema;
    
    @Value("${db.preferredTestQuery}")
    private String testQuery;
    
    @Value("${db.minPoolSize}")
    private int minPoolSize;
    
    @Value("${db.maxPoolSize}")
    private int maxPoolSize;

    /**
     * How long a request waits for a pooled connection before it fails. Hikari's default
     * is 30 s, which lets requests pile up behind a saturated pool; failing after 2 s lets
     * the API answer 503 with Retry-After (RestErrorHandler) while the pool recovers.
     */
    @Value("${db.connectionTimeout:2000}")
    private long connectionTimeout;

    /**
     * Lazy associations are loaded for up to this many owners in one select. The product
     * loaders rely on it to replace their collection fetch joins.
     */
    @Value("${hibernate.default_batch_fetch_size:50}")
    private String batchFetchSize;

    /**
     * true in the test configuration: a query that pages over a collection fetch, which
     * Hibernate can only page in memory after reading every row (HHH000104), fails.
     */
    @Value("${hibernate.query.fail_on_pagination_over_collection_fetch:false}")
    private String failOnCollectionFetchPagination;

    @Bean
    public HikariDataSource dataSource() {
    	HikariDataSource dataSource = DataSourceBuilder.create().type(HikariDataSource.class)
    	.driverClassName(driverClassName)
    	.url(url)
    	.username(user)
    	.password(password)
    	.build();
    	
    	/** Datasource config **/
    	// Fixed-size pool, as Hikari recommends: minimumIdle defaults to the maximum. The old
    	// setIdleTimeout(minPoolSize) passed a pool size as milliseconds and had no effect.
    	dataSource.setMaximumPoolSize(maxPoolSize);
    	dataSource.setConnectionTimeout(connectionTimeout);
    	dataSource.setConnectionTestQuery(testQuery);
    	
    	return dataSource;
    }

	@Bean
	public LocalContainerEntityManagerFactoryBean entityManagerFactory() {

		HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
		vendorAdapter.setGenerateDdl(true);

		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setJpaVendorAdapter(vendorAdapter);
		factory.setPackagesToScan("com.salesmanager.core.model");
		factory.setJpaProperties(additionalProperties());
		factory.setDataSource(dataSource());
		return factory;
	}
	
    final Properties additionalProperties() {
        final Properties hibernateProperties = new Properties();
        
        hibernateProperties.setProperty("hibernate.hbm2ddl.auto", hbm2ddl);
        hibernateProperties.setProperty("hibernate.default_schema", schema);
        hibernateProperties.setProperty("hibernate.dialect", dialect);
        hibernateProperties.setProperty("hibernate.show_sql", showSql);
        hibernateProperties.setProperty("hibernate.cache.use_second_level_cache", "true");
        hibernateProperties.setProperty("hibernate.cache.use_query_cache", "true");
        hibernateProperties.setProperty("hibernate.cache.region.factory_class", "org.hibernate.cache.ehcache.EhCacheRegionFactory");
        hibernateProperties.setProperty("hibernate.connection.CharSet", "utf8");
        hibernateProperties.setProperty("hibernate.connection.characterEncoding", "utf8");
        hibernateProperties.setProperty("hibernate.connection.useUnicode", "true");
        hibernateProperties.setProperty("hibernate.id.new_generator_mappings", "false"); //unless you run on a new schema
        hibernateProperties.setProperty("hibernate.generate_statistics", "false");
        hibernateProperties.setProperty("hibernate.default_batch_fetch_size", batchFetchSize);
        hibernateProperties.setProperty("hibernate.query.fail_on_pagination_over_collection_fetch", failOnCollectionFetchPagination);
        // hibernateProperties.setProperty("hibernate.globally_quoted_identifiers", "true");
        return hibernateProperties;
    }

	@Bean
	public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {

		JpaTransactionManager txManager = new JpaTransactionManager();
		txManager.setEntityManagerFactory(entityManagerFactory);
		return txManager;
	}

}
