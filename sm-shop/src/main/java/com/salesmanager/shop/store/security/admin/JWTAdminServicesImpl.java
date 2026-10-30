package com.salesmanager.shop.store.security.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.user.GroupService;
import com.salesmanager.core.business.services.user.PermissionService;
import com.salesmanager.core.business.services.user.UserService;
import com.salesmanager.core.model.user.Group;
import com.salesmanager.core.model.user.Permission;
import com.salesmanager.core.model.user.User;
import com.salesmanager.shop.admin.security.SecurityDataAccessException;
import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.store.security.user.JWTUser;


@Service("jwtAdminDetailsService")
public class JWTAdminServicesImpl implements UserDetailsService{
	
	private static final Logger LOGGER = LoggerFactory.getLogger(JWTAdminServicesImpl.class);
	
	
	@Inject
	private UserService userService;
	@Inject
	private PermissionService  permissionService;
	@Inject
	private GroupService   groupService;
	
	public final static String ROLE_PREFIX = "ROLE_";//Spring Security 4


	/**
	 * Builds the principal checked on every admin request.
	 * <ul>
	 * <li>enabled follows USERS.ACTIVE, so a deactivated admin is refused on the
	 * next request instead of at token expiry;</li>
	 * <li>lastPasswordResetDate is the last credentials change, so tokens issued
	 * before a password change or reset are refused by JWTTokenUtil.validateToken.</li>
	 * </ul>
	 */
	private UserDetails userDetails(String userName, User user, Collection<GrantedAuthority> authorities) {
		return new JWTUser(
				user.getId(),
				userName,
				user.getFirstName(),
				user.getLastName(),
				user.getAdminEmail(),
				user.getAdminPassword(),
				authorities,
				user.isActive(),
				toWholeSeconds(user.getCredentialsChangedAt())
		);
	}

	/** Token issue times have second precision; compare at the same precision. */
	static Date toWholeSeconds(Date date) {
		return date == null ? null : new Date((date.getTime() / 1000L) * 1000L);
	}

	@Override
	public UserDetails loadUserByUsername(String userName) throws UsernameNotFoundException {
		User user = null;
		Collection<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();

		try {
			
				LOGGER.debug("Loading user by user id: {}", userName);

				user = userService.getByUserName(userName);
			
				if(user==null) {
					//return null;
					throw new UsernameNotFoundException("User " + userName + " not found");
				}

			GrantedAuthority role = new SimpleGrantedAuthority(ROLE_PREFIX + Constants.PERMISSION_AUTHENTICATED);//required to login
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
