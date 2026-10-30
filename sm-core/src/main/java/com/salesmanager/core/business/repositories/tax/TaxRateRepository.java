package com.salesmanager.core.business.repositories.tax;

import java.util.List;

import javax.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.salesmanager.core.model.tax.taxrate.TaxRate;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

	String PASS_DISTINCT_THROUGH = "hibernate.query.passDistinctThrough";

	@Query("select t from TaxRate t join fetch t.taxClass join fetch t.merchantStore tm join fetch t.country left join fetch t.zone left join fetch t.descriptions left join t.parent where tm.id=?1 order by t.taxPriority asc")
	List<TaxRate> findByStore(Integer id);
	
	@Query("select t from TaxRate t join fetch t.taxClass join fetch t.merchantStore tm join fetch t.country left join fetch t.zone left join fetch t.descriptions td left join t.parent where tm.id=?1 and td.language.id=?2 order by t.taxPriority asc")
	List<TaxRate> findByStoreAndLanguage(Integer id, Integer languageId);
	
	@Query("select t from TaxRate t join fetch t.taxClass join fetch t.merchantStore tm join fetch t.country left join fetch t.zone left join fetch t.descriptions td left join t.parent where tm.id=?1 and t.code=?2")
	TaxRate findByStoreAndCode(Integer id, String code);
	
	@Query("select t from TaxRate t join fetch t.taxClass join fetch t.merchantStore tm join fetch t.country left join fetch t.zone left join fetch t.descriptions td left join t.parent where tm.id=?1 and t.id=?2")
	TaxRate findByStoreAndId(Integer store, Long id);
	
	@Query("select t from TaxRate t join fetch t.taxClass join fetch t.merchantStore tm join fetch t.country left join fetch t.zone left join fetch t.descriptions td left join t.parent where t.id=?1")
	TaxRate findOne(Long id);
	
	/**
	 * Rates of one tax class that apply to a country and zone (rates without a
	 * zone apply to the whole country), by ascending priority. Descriptions are
	 * fetched in every language so a rate still applies when it has no
	 * description in the shopper's language. The distinct only removes the
	 * duplicates of the description join in memory and is not sent to SQL.
	 */
	@QueryHints(@QueryHint(name = PASS_DISTINCT_THROUGH, value = "false"))
	@Query("select distinct t from TaxRate t join fetch t.taxClass tcl join fetch t.merchantStore tm join fetch t.country tc left join fetch t.zone tz left join fetch t.descriptions td where tm.id=?1 AND (tz.id=?2 OR tz IS NULL) and tc.id=?3 and tcl.id=?4 order by t.taxPriority asc")
	List<TaxRate> findByMerchantAndZoneAndCountryAndTaxClass(Integer id, Long zoneId, Integer countryId, Long taxClassId);
	
	/**
	 * Same as findByMerchantAndZoneAndCountryAndTaxClass for a free text state
	 * or province (countries without zones).
	 */
	@QueryHints(@QueryHint(name = PASS_DISTINCT_THROUGH, value = "false"))
	@Query("select distinct t from TaxRate t join fetch t.taxClass tcl join fetch t.merchantStore tm join fetch t.country tc left join fetch t.zone tz left join fetch t.descriptions td where tm.id=?1 AND t.stateProvince=?2 and tc.id=?3 and tcl.id=?4 order by t.taxPriority asc")
	List<TaxRate> findByMerchantAndProvinceAndCountryAndTaxClass(Integer id, String province, Integer countryId, Long taxClassId);
	
	
}
