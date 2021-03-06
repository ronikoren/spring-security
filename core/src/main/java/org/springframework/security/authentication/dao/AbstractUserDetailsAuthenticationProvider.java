/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.authentication.dao;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.authority.mapping.NullAuthoritiesMapper;
import org.springframework.security.core.userdetails.UserCache;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.cache.NullUserCache;


import org.springframework.beans.factory.InitializingBean;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.support.MessageSourceAccessor;

import org.springframework.util.Assert;


/**
 * A base {@link AuthenticationProvider} that allows subclasses to override and work with {@link
 * org.springframework.security.core.userdetails.UserDetails} objects. The class is designed to respond to {@link
 * UsernamePasswordAuthenticationToken} authentication requests.
 *
 * <p>
 * Upon successful validation, a <code>UsernamePasswordAuthenticationToken</code> will be created and returned to the
 * caller. The token will include as its principal either a <code>String</code> representation of the username, or the
 * {@link UserDetails} that was returned from the authentication repository. Using <code>String</code> is appropriate
 * if a container adapter is being used, as it expects <code>String</code> representations of the username.
 * Using <code>UserDetails</code> is appropriate if you require access to additional properties of the authenticated
 * user, such as email addresses, human-friendly names etc. As container adapters are not recommended to be used,
 * and <code>UserDetails</code> implementations provide additional flexibility, by default a <code>UserDetails</code>
 * is returned. To override this
 * default, set the {@link #setForcePrincipalAsString} to <code>true</code>.
 * <p>
 * Caching is handled by storing the <code>UserDetails</code> object being placed in the {@link UserCache}. This
 * ensures that subsequent requests with the same username can be validated without needing to query the {@link
 * UserDetailsService}. It should be noted that if a user appears to present an incorrect password, the {@link
 * UserDetailsService} will be queried to confirm the most up-to-date password was used for comparison.
 * Caching is only likely to be required for stateless applications. In a normal web application, for example,
 * the <tt>SecurityContext</tt> is stored in the user's session and the user isn't reauthenticated on
 * each request. The default cache implementation is therefore {@link NullUserCache}.
 *
 * @author Ben Alex
 */
public abstract class AbstractUserDetailsAuthenticationProvider implements AuthenticationProvider, InitializingBean,
        MessageSourceAware {

    protected final Log logger = LogFactory.getLog(getClass());

    //~ Instance fields ================================================================================================

    protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();
    private UserCache userCache = new NullUserCache();
    private boolean forcePrincipalAsString = false;
    protected boolean hideUserNotFoundExceptions = true;
    private UserDetailsChecker preAuthenticationChecks = new DefaultPreAuthenticationChecks();
    private UserDetailsChecker postAuthenticationChecks = new DefaultPostAuthenticationChecks();
    private GrantedAuthoritiesMapper authoritiesMapper = new NullAuthoritiesMapper();

    //~ Methods ========================================================================================================

    /**
     * Allows subclasses to perform any additional checks of a returned (or cached) <code>UserDetails</code>
     * for a given authentication request. Generally a subclass will at least compare the {@link
     * Authentication#getCredentials()} with a {@link UserDetails#getPassword()}. If custom logic is needed to compare
     * additional properties of <code>UserDetails</code> and/or <code>UsernamePasswordAuthenticationToken</code>,
     * these should also appear in this method.
     *
     * @param userDetails as retrieved from the {@link #retrieveUser(String, UsernamePasswordAuthenticationToken)} or
     *        <code>UserCache</code>
     * @param authentication the current request that needs to be authenticated
     *
     * @throws AuthenticationException AuthenticationException if the credentials could not be validated (generally a
     *         <code>BadCredentialsException</code>, an <code>AuthenticationServiceException</code>)
     */
    protected abstract void additionalAuthenticationChecks(UserDetails userDetails,
        UsernamePasswordAuthenticationToken authentication)
        throws AuthenticationException;

    public final void afterPropertiesSet() throws Exception {
        Assert.notNull(this.userCache, "A user cache must be set");
        Assert.notNull(this.messages, "A message source must be set");
        doAfterPropertiesSet();
    }

	/**
	 * purpose of this class is to return the user details as well as cache info during the authenticate()
	 * for simplification the members are package protected to avoid the need to call setters and getters
	 * @author ronik
	 *
	 */
	public class ObtainUserDetailsCallResult {
    	boolean cacheWasUsed; //specifies whether cache was used or not
    	UserDetails user;	  //the user details
    }

	/**
	 * this method is suppose to return null if this is not right authentication provider for this user.
	 * but if it is, then it's suppose to perform the authentication checks and authenticate the user.
	 * sub-classes should override the shouldAuthenticate() method in which they can examine the user
	 * details and determine whether should be authenticating this user. by default the provider will
	 * attempt to authenticate this user 
	 * if authentication fails it throws an exception
	 * @param authentication the authentication object 
	 * @throws AuthenticationException 
	 */
	@Override
	public Authentication authenticate(Authentication authentication)
			throws AuthenticationException {
        Assert.isInstanceOf(UsernamePasswordAuthenticationToken.class, authentication,
                messages.getMessage("AbstractUserDetailsAuthenticationProvider.onlySupports",
                    "Only UsernamePasswordAuthenticationToken is supported"));

        // Determine username        
        String username = getUserName(authentication);

        ObtainUserDetailsCallResult callResult=obtainUserDetails(authentication, username);
        
        Authentication result = null;
        if (shouldAuthenticate(username,callResult)) {
            UserDetails user = callResult.user;
            boolean cacheWasUsed = callResult.cacheWasUsed;

	        try {
	        	checkAndAuthenticateUser(username,user, authentication);
	        } catch (AuthenticationException exception) {
	            if (cacheWasUsed) {
	                // There was a problem, so try again after checking
	                // we're using latest data (i.e. not from the cache)
	            	cacheWasUsed = false;
	            	user = retrieveUser(username, (UsernamePasswordAuthenticationToken) authentication);
	                checkAndAuthenticateUser(username, user, authentication); //2nd time
	            } else {
	                throw exception;
	            }
	        }
	
	        getPostAuthenticationChecks().check(user);
	
	        if (!cacheWasUsed) {
	            getUserCache().putUserInCache(user);
	        }
	
	        result=createSuccessAuthentication(isForcePrincipalAsString() ? user.getUsername() : user, authentication, user);
        }
        return result;

	}

	/**
	 * called by the authenticate method to get the user name based on the authentication 
	 * @param authentication the authentication object
	 * @return the user name
	 */
	protected String getUserName(Authentication authentication) {
		return (authentication.getPrincipal() == null) ? "NONE_PROVIDED" : authentication.getName();
	}

	/**
	 * called by the authenticate method to when this provider is supposed to perform the authentication of this user
	 * perform pre authentication checks, and perform the actual authentication
	 * throw an exception if the checks or authenticaion fail 
	 * @param username the user name
	 * @param user the usr details
	 * @param authentication the authentication info
	 */
	protected void checkAndAuthenticateUser(String username, UserDetails user,Authentication authentication) {
        getPreAuthenticationChecks().check(user);
        additionalAuthenticationChecks(user, (UsernamePasswordAuthenticationToken) authentication);
	}

	/**
	 * called by the authenticate method to check based on the user details whether 'this' is the right authentication provider
	 * for this user  
	 * @param username the username
	 * @param callResult the user details and whether it was obtained from the cache or not
	 * @return true if this provider should do the authentication for this user, false otherwise
	 */
	protected boolean shouldAuthenticate(String username, ObtainUserDetailsCallResult callResult) {
		return true; //default
	}

	/**
	 * called by the authenticate method to obtain the user details  
	 * @param authentication the authentication obejct
	 * @param username the user name
	 * @return an object which contains the user details but also specifies whether cache was used for that
	 */
	protected ObtainUserDetailsCallResult obtainUserDetails(Authentication authentication,String username) {
		
		ObtainUserDetailsCallResult callResult=new ObtainUserDetailsCallResult();
		callResult.cacheWasUsed = true;
		callResult.user = getUserCache().getUserFromCache(username);

        if (callResult.user == null) {
        	callResult.cacheWasUsed = false;

            try {
            	callResult.user = retrieveUser(username, (UsernamePasswordAuthenticationToken) authentication);
            } catch (UsernameNotFoundException notFound) {
                userNotFound(username, notFound); //may re-throw the exception
            }

            Assert.notNull(callResult.user, "retrieveUser returned null - a violation of the interface contract");
        }

		return callResult; 
	}

	/**
	 * this method may be called by a sub-class when the user entity is not known to this provider
	 * and a UsernameNotFoundException is caught
	 * @param username the user name of the user trying to be authenticated
	 * @param notFound the exception caught
	 */
	protected void userNotFound(String username,
			UsernameNotFoundException notFound) {
		_logger.debug("User '" + username + "' not found");

		if (hideUserNotFoundExceptions) {
		    throw new BadCredentialsException(messages.getMessage(
		            "AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad credentials"));
		} else {
		    throw notFound; //re-throw
		}
	}

    /**
     * Creates a successful {@link Authentication} object.<p>Protected so subclasses can override.</p>
     *  <p>Subclasses will usually store the original credentials the user supplied (not salted or encoded
     * passwords) in the returned <code>Authentication</code> object.</p>
     *
     * @param principal that should be the principal in the returned object (defined by the {@link
     *        #isForcePrincipalAsString()} method)
     * @param authentication that was presented to the provider for validation
     * @param user that was loaded by the implementation
     *
     * @return the successful authentication token
     */
    protected Authentication createSuccessAuthentication(Object principal, Authentication authentication,
            UserDetails user) {
        // Ensure we return the original credentials the user supplied,
        // so subsequent attempts are successful even with encoded passwords.
        // Also ensure we return the original getDetails(), so that future
        // authentication events after cache expiry contain the details
        UsernamePasswordAuthenticationToken result = new UsernamePasswordAuthenticationToken(principal,
                authentication.getCredentials(), authoritiesMapper.mapAuthorities(user.getAuthorities()));
        result.setDetails(authentication.getDetails());

        return result;
    }

    protected void doAfterPropertiesSet() throws Exception {}

    public UserCache getUserCache() {
        return userCache;
    }

    public boolean isForcePrincipalAsString() {
        return forcePrincipalAsString;
    }

    public boolean isHideUserNotFoundExceptions() {
        return hideUserNotFoundExceptions;
    }

    /**
     * Allows subclasses to actually retrieve the <code>UserDetails</code> from an implementation-specific
     * location, with the option of throwing an <code>AuthenticationException</code> immediately if the presented
     * credentials are incorrect (this is especially useful if it is necessary to bind to a resource as the user in
     * order to obtain or generate a <code>UserDetails</code>).<p>Subclasses are not required to perform any
     * caching, as the <code>AbstractUserDetailsAuthenticationProvider</code> will by default cache the
     * <code>UserDetails</code>. The caching of <code>UserDetails</code> does present additional complexity as this
     * means subsequent requests that rely on the cache will need to still have their credentials validated, even if
     * the correctness of credentials was assured by subclasses adopting a binding-based strategy in this method.
     * Accordingly it is important that subclasses either disable caching (if they want to ensure that this method is
     * the only method that is capable of authenticating a request, as no <code>UserDetails</code> will ever be
     * cached) or ensure subclasses implement {@link #additionalAuthenticationChecks(UserDetails,
     * UsernamePasswordAuthenticationToken)} to compare the credentials of a cached <code>UserDetails</code> with
     * subsequent authentication requests.</p>
     *  <p>Most of the time subclasses will not perform credentials inspection in this method, instead
     * performing it in {@link #additionalAuthenticationChecks(UserDetails, UsernamePasswordAuthenticationToken)} so
     * that code related to credentials validation need not be duplicated across two methods.</p>
     *
     * @param username The username to retrieve
     * @param authentication The authentication request, which subclasses <em>may</em> need to perform a binding-based
     *        retrieval of the <code>UserDetails</code>
     *
     * @return the user information (never <code>null</code> - instead an exception should the thrown)
     *
     * @throws AuthenticationException if the credentials could not be validated (generally a
     *         <code>BadCredentialsException</code>, an <code>AuthenticationServiceException</code> or
     *         <code>UsernameNotFoundException</code>)
     */
    protected abstract UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication)
        throws AuthenticationException;

    public void setForcePrincipalAsString(boolean forcePrincipalAsString) {
        this.forcePrincipalAsString = forcePrincipalAsString;
    }

    /**
     * By default the <code>AbstractUserDetailsAuthenticationProvider</code> throws a
     * <code>BadCredentialsException</code> if a username is not found or the password is incorrect. Setting this
     * property to <code>false</code> will cause <code>UsernameNotFoundException</code>s to be thrown instead for the
     * former. Note this is considered less secure than throwing <code>BadCredentialsException</code> for both
     * exceptions.
     *
     * @param hideUserNotFoundExceptions set to <code>false</code> if you wish <code>UsernameNotFoundException</code>s
     *        to be thrown instead of the non-specific <code>BadCredentialsException</code> (defaults to
     *        <code>true</code>)
     */
    public void setHideUserNotFoundExceptions(boolean hideUserNotFoundExceptions) {
        this.hideUserNotFoundExceptions = hideUserNotFoundExceptions;
    }

    public void setMessageSource(MessageSource messageSource) {
        this.messages = new MessageSourceAccessor(messageSource);
    }

    public void setUserCache(UserCache userCache) {
        this.userCache = userCache;
    }

    public boolean supports(Class<?> authentication) {
        return (UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication));
    }

    protected UserDetailsChecker getPreAuthenticationChecks() {
        return preAuthenticationChecks;
    }

    /**
     * Sets the policy will be used to verify the status of the loaded <tt>UserDetails</tt> <em>before</em>
     * validation of the credentials takes place.
     *
     * @param preAuthenticationChecks strategy to be invoked prior to authentication.
     */
    public void setPreAuthenticationChecks(UserDetailsChecker preAuthenticationChecks) {
        this.preAuthenticationChecks = preAuthenticationChecks;
    }

    protected UserDetailsChecker getPostAuthenticationChecks() {
        return postAuthenticationChecks;
    }

    public void setPostAuthenticationChecks(UserDetailsChecker postAuthenticationChecks) {
        this.postAuthenticationChecks = postAuthenticationChecks;
    }

    public void setAuthoritiesMapper(GrantedAuthoritiesMapper authoritiesMapper) {
        this.authoritiesMapper = authoritiesMapper;
    }

    private class DefaultPreAuthenticationChecks implements UserDetailsChecker {
        public void check(UserDetails user) {
            if (!user.isAccountNonLocked()) {
                logger.debug("User account is locked");

                throw new LockedException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.locked",
                        "User account is locked"), user);
            }

            if (!user.isEnabled()) {
                logger.debug("User account is disabled");

                throw new DisabledException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.disabled",
                        "User is disabled"), user);
            }

            if (!user.isAccountNonExpired()) {
                logger.debug("User account is expired");

                throw new AccountExpiredException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.expired",
                        "User account has expired"), user);
            }
        }
    }

    private class DefaultPostAuthenticationChecks implements UserDetailsChecker {
        public void check(UserDetails user) {
            if (!user.isCredentialsNonExpired()) {
                logger.debug("User account credentials have expired");

                throw new CredentialsExpiredException(messages.getMessage(
                        "AbstractUserDetailsAuthenticationProvider.credentialsExpired",
                        "User credentials have expired"), user);
            }
        }
    }
}
