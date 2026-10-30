package com.salesmanager.shop.store.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.user.GroupService;
import com.salesmanager.core.business.services.user.PermissionService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.user.Group;
import com.salesmanager.core.model.user.Permission;
import com.salesmanager.shop.admin.security.SecurityDataAccessException;
import com.salesmanager.shop.constants.Constants;

public abstract class AbstractCustomerServices implements UserDetailsService{
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCustomerServices.class);
	
	protected CustomerService customerService;
	protected PermissionService  permissionService;
	protected GroupService   groupService;
	
	public final static String ROLE_PREFIX = "ROLE_";//Spring Security 4

	/** Request parameter naming the store, as read by MerchantStoreArgumentResolver. */
	public final static String STORE_PARAMETER = "store";
	
	public AbstractCustomerServices(
			CustomerService customerService, 
			PermissionService permissionService, 
			GroupService groupService) {
		
		this.customerService = customerService;
		this.permissionService = permissionService;
		this.groupService = groupService;
	}
	
	protected abstract UserDetails userDetails(String userName, Customer customer, Collection<GrantedAuthority> authorities);
	

	/**
	 * Store code of a request: the store parameter, or the default store when it
	 * is absent. This is the same rule MerchantStoreArgumentResolver applies, so a
	 * customer is always looked up in the store the endpoint works on.
	 */
	public static String requestStoreCode(HttpServletRequest request) {
		String code = request == null ? null : request.getParameter(STORE_PARAMETER);
		return StringUtils.isBlank(code) ? com.salesmanager.core.business.constants.Constants.DEFAULT_STORE : code;
	}

	/**
	 * Store code of the request being processed on this thread, or the default
	 * store outside a request.
	 */
	public static String currentRequestStoreCode() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		HttpServletRequest request = attributes instanceof ServletRequestAttributes
				? ((ServletRequestAttributes) attributes).getRequest()
				: null;
		return requestStoreCode(request);
	}

	/**
	 * Customer nicks are unique per store only. Spring Security's username and
	 * password authentication calls this method with the username alone, so the
	 * store is taken from the current request (see {@link #currentRequestStoreCode()}).
	 */
	@Override
	public UserDetails loadUserByUsername(String userName)
			throws UsernameNotFoundException, DataAccessException {
		return loadUserByUsernameAndStore(userName, currentRequestStoreCode());
	}

	/**
	 * Loads the customer with this nick in this store only.
	 */
	public UserDetails loadUserByUsernameAndStore(String userName, String storeCode)
			throws UsernameNotFoundException, DataAccessException {
		Customer user = null;
		Collection<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();

		try {
			
				LOGGER.debug("Loading user by user id: {} in store {}", userName, storeCode);

				user = customerService.getByNick(userName, storeCode);
			
				if(user==null) {
					//return null;
					throw new UsernameNotFoundException("User " + userName + " not found");
				}
	
	

			GrantedAuthority role = new SimpleGrantedAuthority(ROLE_PREFIX + Constants.PERMISSION_CUSTOMER_AUTHENTICATED);//required to login
			authorities.add(role); 
			
			List<Integer> groupsId = new ArrayList<Integer>();
			List<Group> groups = user.getGroups();
			for(Group group : groups) {
				groupsId.add(group.getId());
			}
			
	
			if(CollectionUtils.isNotEmpty(groupsId)) {
		    	List<Permission> permissions = permissionService.getPermissions(groupsId);
		    	for(Permission permission : permissions) {
		    		GrantedAuthority auth = new SimpleGrantedAuthority(permission.getPermissionName());
		    		authorities.add(auth);
		    	}
			}
			

		} catch (ServiceException e) {
			LOGGER.error("Exception while querrying customer",e);
			throw new SecurityDataAccessException("Cannot authenticate customer",e);
		}

		return userDetails(userName, user, authorities);
		
	}

}
