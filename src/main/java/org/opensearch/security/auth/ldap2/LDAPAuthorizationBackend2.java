/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.security.auth.ldap2;

import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import com.google.common.collect.HashMultimap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.OpenSearchSecurityException;
import org.opensearch.SpecialPermission;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.Strings;
import org.opensearch.security.auth.AuthenticationContext;
import org.opensearch.security.auth.AuthorizationBackend;
import org.opensearch.security.auth.Destroyable;
import org.opensearch.security.auth.ldap.util.ConfigConstants;
import org.opensearch.security.auth.ldap.util.LdapHelper;
import org.opensearch.security.auth.ldap.util.Utils;
import org.opensearch.security.support.WildcardMatcher;
import org.opensearch.security.user.User;
import org.opensearch.security.util.SettingsBasedSSLConfigurator.SSLConfigException;

import org.ldaptive.Connection;
import org.ldaptive.ConnectionFactory;
import org.ldaptive.LdapAttribute;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.ReturnAttributes;
import org.ldaptive.SearchFilter;
import org.ldaptive.SearchScope;
import org.ldaptive.pool.ConnectionPool;

public class LDAPAuthorizationBackend2 implements AuthorizationBackend, Destroyable {

    static final int ZERO_PLACEHOLDER = 0;
    static final int ONE_PLACEHOLDER = 1;
    static final int TWO_PLACEHOLDER = 2;
    static final String DEFAULT_ROLEBASE = "";
    static final String DEFAULT_ROLESEARCH = "(member={0})";
    static final String DEFAULT_ROLENAME = "name";
    static final String DEFAULT_USERROLENAME = "memberOf";

    protected static final Logger log = LogManager.getLogger(LDAPAuthorizationBackend2.class);
    private final Settings settings;
    private final WildcardMatcher skipUsersMatcher;
    private final WildcardMatcher excludeRolesMatcher;
    private final WildcardMatcher nestedRoleMatcher;
    private final List<Map.Entry<String, Settings>> roleBaseSettings;
    private ConnectionPool connectionPool;
    private ConnectionFactory connectionFactory;
    private LDAPUserSearcher userSearcher;
    private final String[] returnAttributes;
    private final boolean shouldFollowReferrals;

    public LDAPAuthorizationBackend2(final Settings settings, final Path configPath) throws SSLConfigException {
        this.settings = settings;
        this.skipUsersMatcher = WildcardMatcher.from(settings.getAsList(ConfigConstants.LDAP_AUTHZ_SKIP_USERS));
        this.excludeRolesMatcher = WildcardMatcher.from(settings.getAsList(ConfigConstants.LDAP_AUTHZ_EXCLUDE_ROLES));
        this.nestedRoleMatcher = settings.getAsBoolean(ConfigConstants.LDAP_AUTHZ_RESOLVE_NESTED_ROLES, false)
            ? WildcardMatcher.from(settings.getAsList(ConfigConstants.LDAP_AUTHZ_NESTEDROLEFILTER))
            : null;
        this.roleBaseSettings = getRoleSearchSettings(settings);

        LDAPConnectionFactoryFactory ldapConnectionFactoryFactory = new LDAPConnectionFactoryFactory(settings, configPath);

        this.connectionPool = ldapConnectionFactoryFactory.createConnectionPool();
        this.connectionFactory = ldapConnectionFactoryFactory.createConnectionFactory(this.connectionPool);
        this.userSearcher = new LDAPUserSearcher(settings);
        this.returnAttributes = settings.getAsList(ConfigConstants.LDAP_RETURN_ATTRIBUTES, Arrays.asList(ReturnAttributes.ALL.value()))
            .toArray(new String[0]);
        this.shouldFollowReferrals = settings.getAsBoolean(ConfigConstants.FOLLOW_REFERRALS, ConfigConstants.FOLLOW_REFERRALS_DEFAULT);
    }

    private static List<Map.Entry<String, Settings>> getRoleSearchSettings(Settings settings) {
        Map<String, Settings> groupedSettings = settings.getGroups(ConfigConstants.LDAP_AUTHZ_ROLES, true);

        if (!groupedSettings.isEmpty()) {
            // New style settings
            return Utils.getOrderedBaseSettings(groupedSettings);
        } else {
            // Old style settings
            return convertOldStyleSettingsToNewStyle(settings);
        }
    }

    private static List<Map.Entry<String, Settings>> convertOldStyleSettingsToNewStyle(Settings settings) {
        Map<String, Settings> result = new HashMap<>(1);

        Settings.Builder settingsBuilder = Settings.builder();

        settingsBuilder.put(ConfigConstants.LDAP_AUTHCZ_BASE, settings.get(ConfigConstants.LDAP_AUTHZ_ROLEBASE, DEFAULT_ROLEBASE));
        settingsBuilder.put(ConfigConstants.LDAP_AUTHCZ_SEARCH, settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH));

        result.put("convertedOldStyleSettings", settingsBuilder.build());

        return Collections.singletonList(result.entrySet().iterator().next());
    }

    @SuppressWarnings("removal")
    @Override
    public User addRoles(final User user, AuthenticationContext context) throws OpenSearchSecurityException {

        final SecurityManager sm = System.getSecurityManager();

        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }

        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<User>() {
                @Override
                public User run() throws Exception {
                    return addRoles0(user, context);
                }
            });
        } catch (PrivilegedActionException e) {
            if (e.getException() instanceof OpenSearchSecurityException) {
                throw (OpenSearchSecurityException) e.getException();
            } else if (e.getException() instanceof RuntimeException) {
                throw (RuntimeException) e.getException();
            } else {
                throw new RuntimeException(e.getException());
            }
        }
    }

    private User addRoles0(final User user, AuthenticationContext context) throws OpenSearchSecurityException {

        if (user == null) {
            return user;
        }

        String authenticatedUser;
        String originalUserName;
        LdapEntry entry = context.getContextData(LdapEntry.class).orElse(null);
        String dn = null;

        if (entry != null) {
            dn = entry.getDn();
            authenticatedUser = entry.getDn();
            originalUserName = context.getCredentials().getUsername();
        } else {
            authenticatedUser = user.getName();
            originalUserName = user.getName();
        }

        final boolean rolesearchEnabled = settings.getAsBoolean(ConfigConstants.LDAP_AUTHZ_ROLESEARCH_ENABLED, true);

        final boolean isDebugEnabled = log.isDebugEnabled();
        if (isDebugEnabled) {
            log.debug("Try to get roles for {}", authenticatedUser);
        }

        final boolean isTraceEnabled = log.isTraceEnabled();
        if (isTraceEnabled) {
            log.trace("user class: {}", user.getClass());
            log.trace("authenticatedUser: {}", authenticatedUser);
            log.trace("originalUserName: {}", originalUserName);
            log.trace("entry: {}", String.valueOf(entry));
            log.trace("dn: {}", dn);
        }

        if (skipUsersMatcher.test(authenticatedUser)) {
            log.debug("Skipped search roles of user {}/{}", authenticatedUser, originalUserName);
            return user;
        }

        Set<String> additionalRoles = new HashSet<>();

        try (Connection connection = this.connectionFactory.getConnection()) {

            connection.open();

            if (entry == null || dn == null) {

                if (isValidDn(authenticatedUser)) {
                    // assume dn
                    if (isTraceEnabled) {
                        log.trace("{} is a valid DN", authenticatedUser);
                    }

                    entry = LdapHelper.lookup(connection, authenticatedUser, this.returnAttributes, this.shouldFollowReferrals);

                    if (entry == null) {
                        throw new OpenSearchSecurityException("No user '" + authenticatedUser + "' found");
                    }

                } else {
                    entry = this.userSearcher.exists(connection, user.getName(), this.returnAttributes, this.shouldFollowReferrals);

                    if (isTraceEnabled) {
                        log.trace("{} is not a valid DN and was resolved to {}", authenticatedUser, entry);
                    }

                    if (entry == null || entry.getDn() == null) {
                        throw new OpenSearchSecurityException("No user " + authenticatedUser + " found");
                    }
                }

                dn = entry.getDn();

                if (isTraceEnabled) {
                    log.trace("User found with DN {}", dn);
                }
            }

            final Set<LdapName> ldapRoles = new HashSet<>(150);
            final Set<String> nonLdapRoles = new HashSet<>(150);
            final HashMultimap<LdapName, Map.Entry<String, Settings>> resultRoleSearchBaseKeys = HashMultimap.create();

            // Roles as an attribute of the user entry
            // default is userrolename: memberOf
            final String userRoleNames = settings.get(ConfigConstants.LDAP_AUTHZ_USERROLENAME, DEFAULT_USERROLENAME);

            if (isTraceEnabled) {
                log.trace("raw userRoleName(s): {}", userRoleNames);
            }

            // we support more than one rolenames, must be separated by a comma
            for (String userRoleName : userRoleNames.split(",")) {
                final String roleName = userRoleName.trim();
                if (entry.getAttribute(roleName) != null) {
                    final Collection<String> userRoles = entry.getAttribute(roleName).getStringValues();
                    for (final String possibleRoleDN : userRoles) {
                        if (isValidDn(possibleRoleDN)) {
                            LdapName ldapName = new LdapName(possibleRoleDN);
                            ldapRoles.add(ldapName);
                            resultRoleSearchBaseKeys.putAll(ldapName, this.roleBaseSettings);
                        } else {
                            nonLdapRoles.add(possibleRoleDN);
                        }
                    }
                }
            }

            if (isTraceEnabled) {
                log.trace("User attr. ldap roles count: {}", ldapRoles.size());
                log.trace("User attr. ldap roles {}", ldapRoles);
                log.trace("User attr. non-ldap roles count: {}", nonLdapRoles.size());
                log.trace("User attr. non-ldap roles {}", nonLdapRoles);

            }

            // The attribute in a role entry containing the name of that role, Default is
            // "name".
            // Can also be "dn" to use the full DN as rolename.
            // rolename: name
            final String roleName = settings.get(ConfigConstants.LDAP_AUTHZ_ROLENAME, DEFAULT_ROLENAME);

            if (isTraceEnabled) {
                log.trace("roleName: {}", roleName);
            }

            // Specify the name of the attribute which value should be substituted with {2}
            // Substituted with an attribute value from user's directory entry, of the
            // authenticated user
            // userroleattribute: null
            final String userRoleAttributeName = settings.get(ConfigConstants.LDAP_AUTHZ_USERROLEATTRIBUTE, null);

            if (isTraceEnabled) {
                log.trace("userRoleAttribute: {}", userRoleAttributeName);
                log.trace("rolesearch: {}", settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH));
            }

            String userRoleAttributeValue = null;
            final LdapAttribute userRoleAttribute = entry.getAttribute(userRoleAttributeName);

            if (userRoleAttribute != null) {
                userRoleAttributeValue = Utils.getSingleStringValue(userRoleAttribute);
            }

            if (rolesearchEnabled) {
                String escapedDn = dn;

                for (Map.Entry<String, Settings> roleSearchSettingsEntry : roleBaseSettings) {
                    Settings roleSearchSettings = roleSearchSettingsEntry.getValue();

                    SearchFilter f = new SearchFilter();
                    f.setFilter(roleSearchSettings.get(ConfigConstants.LDAP_AUTHCZ_SEARCH, DEFAULT_ROLESEARCH));
                    f.setParameter(ZERO_PLACEHOLDER, escapedDn);
                    f.setParameter(ONE_PLACEHOLDER, originalUserName);
                    f.setParameter(TWO_PLACEHOLDER, userRoleAttributeValue == null ? TWO_PLACEHOLDER : userRoleAttributeValue);

                    List<LdapEntry> rolesResult = LdapHelper.search(
                        connection,
                        roleSearchSettings.get(ConfigConstants.LDAP_AUTHCZ_BASE, DEFAULT_ROLEBASE),
                        f,
                        SearchScope.SUBTREE,
                        this.returnAttributes,
                        this.shouldFollowReferrals
                    );

                    if (isTraceEnabled) {
                        log.trace(
                            "Results for LDAP group search for {} in base {}:\n{}",
                            escapedDn,
                            roleSearchSettingsEntry.getKey(),
                            rolesResult
                        );
                    }

                    if (rolesResult != null && !rolesResult.isEmpty()) {
                        for (final Iterator<LdapEntry> iterator = rolesResult.iterator(); iterator.hasNext();) {
                            LdapEntry searchResultEntry = iterator.next();
                            LdapName ldapName = new LdapName(searchResultEntry.getDn());
                            if (!excludeRolesMatcher.test(searchResultEntry.getDn())) {
                                ldapRoles.add(ldapName);
                                resultRoleSearchBaseKeys.put(ldapName, roleSearchSettingsEntry);
                            }
                        }
                    }
                }
            }

            if (isTraceEnabled) {
                log.trace("roles count total {}", ldapRoles.size());
            }

            // nested roles, makes only sense for DN style role names
            if (nestedRoleMatcher != null) {

                if (isTraceEnabled) {
                    log.trace("Evaluate nested roles");
                }

                final Set<LdapName> nestedReturn = new HashSet<>(ldapRoles);

                for (final LdapName roleLdapName : ldapRoles) {
                    Set<Map.Entry<String, Settings>> nameRoleSearchBaseKeys = resultRoleSearchBaseKeys.get(roleLdapName);

                    if (nameRoleSearchBaseKeys == null) {
                        log.error("Could not find roleSearchBaseKeys for {}; existing: {}", roleLdapName, resultRoleSearchBaseKeys);
                        continue;
                    }

                    final Set<LdapName> nestedRoles = resolveNestedRoles(
                        roleLdapName,
                        connection,
                        userRoleNames,
                        0,
                        rolesearchEnabled,
                        nameRoleSearchBaseKeys
                    );

                    if (isTraceEnabled) {
                        log.trace("{} nested roles for {}", nestedRoles.size(), roleLdapName);
                    }

                    nestedReturn.addAll(nestedRoles);
                }

                for (final LdapName roleLdapName : nestedReturn) {
                    final String role = getRoleFromEntry(connection, roleLdapName, roleName);

                    if (excludeRolesMatcher.test(role)) {
                        if (isDebugEnabled) {
                            log.debug("Role was excluded or empty attribute '{}' for entry {}", roleName, roleLdapName);
                        }
                    } else {
                        additionalRoles.add(role);
                    }
                }

            } else {
                // DN roles, extract rolename according to config
                for (final LdapName roleLdapName : ldapRoles) {
                    final String role = getRoleFromEntry(connection, roleLdapName, roleName);

                    if (excludeRolesMatcher.test(role)) {
                        if (isDebugEnabled) {
                            log.debug("Role was excluded or empty attribute '{}' for entry {}", roleName, roleLdapName);
                        }
                    } else {
                        additionalRoles.add(role);
                    }
                }

            }

            // add all non-LDAP roles from user attributes to the final set of backend roles
            additionalRoles.addAll(nonLdapRoles);

            if (isDebugEnabled) {
                log.debug("Roles for {} -> {}", user.getName(), user.getRoles());
            }

            if (isTraceEnabled) {
                log.trace("returned user: {}", user);
            }

            return user.withRoles(additionalRoles);
        } catch (final Exception e) {
            if (isDebugEnabled) {
                log.debug("Unable to fill user roles due to ", e);
            }
            throw new OpenSearchSecurityException(e.toString(), e);
        }

    }

    protected Set<LdapName> resolveNestedRoles(
        final LdapName roleDn,
        final Connection ldapConnection,
        String userRoleName,
        int depth,
        final boolean rolesearchEnabled,
        Set<Map.Entry<String, Settings>> roleSearchBaseSettingsSet
    ) throws OpenSearchSecurityException, LdapException {

        final boolean isTraceEnabled = log.isTraceEnabled();
        if (nestedRoleMatcher.test(roleDn.toString())) {

            if (isTraceEnabled) {
                log.trace("Filter nested role {}", roleDn);
            }

            return Collections.emptySet();
        }

        depth++;

        final Set<LdapName> result = new HashSet<>(20);
        final HashMultimap<LdapName, Map.Entry<String, Settings>> resultRoleSearchBaseKeys = HashMultimap.create();

        final LdapEntry e0 = LdapHelper.lookup(ldapConnection, roleDn.toString(), this.returnAttributes, this.shouldFollowReferrals);
        final boolean isDebugEnabled = log.isDebugEnabled();

        if (e0.getAttribute(userRoleName) != null) {
            final Collection<String> userRoles = e0.getAttribute(userRoleName).getStringValues();
            for (final String possibleRoleDN : userRoles) {
                if (isValidDn(possibleRoleDN)) {
                    try {
                        LdapName ldapName = new LdapName(possibleRoleDN);
                        result.add(ldapName);
                        resultRoleSearchBaseKeys.putAll(ldapName, this.roleBaseSettings);
                    } catch (InvalidNameException e) {
                        // ignore
                    }
                } else {
                    if (isDebugEnabled) {
                        log.debug("Cannot add {} as a role because its not a valid dn", possibleRoleDN);
                    }
                }
            }
        }

        if (isTraceEnabled) {
            log.trace("result nested attr count for depth {} : {}", depth, result.size());
        }

        if (rolesearchEnabled) {
            String escapedDn = roleDn.toString();

            for (Map.Entry<String, Settings> roleSearchBaseSettingsEntry : Utils.getOrderedBaseSettings(roleSearchBaseSettingsSet)) {
                Settings roleSearchSettings = roleSearchBaseSettingsEntry.getValue();

                SearchFilter f = new SearchFilter();
                f.setFilter(roleSearchSettings.get(ConfigConstants.LDAP_AUTHCZ_SEARCH, DEFAULT_ROLESEARCH));
                f.setParameter(ZERO_PLACEHOLDER, escapedDn);
                f.setParameter(ONE_PLACEHOLDER, escapedDn);

                List<LdapEntry> foundEntries = LdapHelper.search(
                    ldapConnection,
                    roleSearchSettings.get(ConfigConstants.LDAP_AUTHCZ_BASE, DEFAULT_ROLEBASE),
                    f,
                    SearchScope.SUBTREE,
                    this.returnAttributes,
                    this.shouldFollowReferrals
                );

                if (isTraceEnabled) {
                    log.trace(
                        "Results for LDAP group search for {} in base {}:\n{}",
                        escapedDn,
                        roleSearchBaseSettingsEntry.getKey(),
                        foundEntries
                    );
                }

                if (foundEntries != null) {
                    for (final LdapEntry entry : foundEntries) {
                        try {
                            final LdapName dn = new LdapName(entry.getDn());
                            result.add(dn);
                            resultRoleSearchBaseKeys.put(dn, roleSearchBaseSettingsEntry);
                        } catch (final InvalidNameException e) {
                            throw new LdapException(e);
                        }
                    }
                }
            }
        }

        int maxDepth = ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH_DEFAULT;
        try {
            maxDepth = settings.getAsInt(ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH, ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH_DEFAULT);
        } catch (Exception e) {
            log.error(ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH + " is not parseable: ", e);
        }

        if (depth < maxDepth) {
            for (final LdapName nm : new HashSet<LdapName>(result)) {
                Set<Map.Entry<String, Settings>> nameRoleSearchBaseKeys = resultRoleSearchBaseKeys.get(nm);

                if (nameRoleSearchBaseKeys == null) {
                    log.error("Could not find roleSearchBaseKeys for {}; existing: {}", nm, resultRoleSearchBaseKeys);
                    continue;
                }

                final Set<LdapName> in = resolveNestedRoles(
                    nm,
                    ldapConnection,
                    userRoleName,
                    depth,
                    rolesearchEnabled,
                    nameRoleSearchBaseKeys
                );
                result.addAll(in);
            }
        }

        return result;
    }

    @Override
    public String getType() {
        return "ldap";
    }

    private boolean isValidDn(final String dn) {

        if (Strings.isNullOrEmpty(dn)) {
            return false;
        }

        try {
            new LdapName(dn);
        } catch (final Exception e) {
            return false;
        }

        return true;
    }

    private String getRoleFromEntry(final Connection ldapConnection, final LdapName ldapName, final String role) {

        if (ldapName == null || Strings.isNullOrEmpty(role)) {
            return null;
        }

        if ("dn".equalsIgnoreCase(role)) {
            return ldapName.toString();
        }

        try {
            final LdapEntry roleEntry = LdapHelper.lookup(
                ldapConnection,
                ldapName.toString(),
                this.returnAttributes,
                this.shouldFollowReferrals
            );

            if (roleEntry != null) {
                final LdapAttribute roleAttribute = roleEntry.getAttribute(role);
                if (roleAttribute != null) {
                    return Utils.getSingleStringValue(roleAttribute);
                }
            }
        } catch (LdapException e) {
            log.error("Unable to handle role {} because of ", ldapName, e);
        }

        return null;
    }

    @Override
    public void destroy() {
        if (this.connectionPool != null) {
            this.connectionPool.close();
            this.connectionPool = null;
        }
    }

}
