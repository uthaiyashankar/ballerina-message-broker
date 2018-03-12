/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.ballerina.messaging.broker.auth.BrokerAuthConfiguration;
import io.ballerina.messaging.broker.auth.authorization.AuthResourceStore;
import io.ballerina.messaging.broker.auth.authorization.AuthScopeStore;
import io.ballerina.messaging.broker.auth.authorization.Authorizer;
import io.ballerina.messaging.broker.auth.authorization.UserStore;
import io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms.resource.AuthResource;
import io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms.resource.AuthResourceStoreImpl;
import io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms.resource.ResourceCacheKey;
import io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms.scope.AuthScopeStoreImpl;
import io.ballerina.messaging.broker.auth.exception.BrokerAuthDuplicateException;
import io.ballerina.messaging.broker.auth.exception.BrokerAuthException;
import io.ballerina.messaging.broker.auth.exception.BrokerAuthNotFoundException;
import io.ballerina.messaging.broker.auth.exception.BrokerAuthServerException;
import io.ballerina.messaging.broker.common.BrokerClassLoader;
import io.ballerina.messaging.broker.common.StartupContext;
import io.ballerina.messaging.broker.common.config.BrokerConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.sql.DataSource;

/**
 * Class provides database based @{@link Authorizer} implementation.
 */
public class RdbmsAuthorizer implements Authorizer {

    public static final String USER_STORE_CLASS_PROPERTY_NAME = "userStore";
    private static final Logger LOGGER = LoggerFactory.getLogger(RdbmsAuthorizer.class);

    private AuthScopeStore authScopeStore;

    private AuthResourceStore authResourceStore;

    private UserStore userStore;

    /**
     * Cache which store user id vs  user cache entry
     */
    private LoadingCache<String, UserCacheEntry> userCache;

    @Override
    public void initialize(StartupContext startupContext, Map<String, String> properties)
            throws Exception {

        DataSource dataSource = startupContext.getService(DataSource.class);
        BrokerConfigProvider configProvider = startupContext.getService(BrokerConfigProvider.class);
        BrokerAuthConfiguration brokerAuthConfiguration = configProvider.getConfigurationObject(
                BrokerAuthConfiguration.NAMESPACE, BrokerAuthConfiguration.class);



        UserStore userStore = createAuthProvider(startupContext, properties);

        authResourceStore = new AuthResourceStoreImpl(brokerAuthConfiguration, dataSource, userStore);
        authScopeStore = new AuthScopeStoreImpl(brokerAuthConfiguration, dataSource);
        userCache = CacheBuilder.newBuilder()
                                .maximumSize(brokerAuthConfiguration.getAuthorization().getCache()
                                                                    .getSize())
                                .expireAfterWrite(brokerAuthConfiguration.getAuthorization()
                                                                         .getCache()
                                                                         .getTimeout(),
                                                  TimeUnit.MINUTES)
                                .build(new UserCacheLoader());
    }

    private UserStore createAuthProvider(StartupContext startupContext, Map<String, String> properties)
            throws Exception {
        String userStoreClassName = properties.get(USER_STORE_CLASS_PROPERTY_NAME);

        if (Objects.nonNull(userStoreClassName)) {
            userStore = BrokerClassLoader.loadClass(userStoreClassName, UserStore.class);
            userStore.initialize(startupContext);
            return userStore;
        } else {
            throw new RuntimeException("Please configure a user store for " + RdbmsAuthorizer.class.getCanonicalName());
        }

    }

    @Override
    public boolean authorize(String scopeName, String userId)
            throws BrokerAuthException, BrokerAuthServerException, BrokerAuthNotFoundException {
        try {
            if (userId != null) {
                UserCacheEntry userCacheEntry = userCache.get(userId);
                boolean anyMatch = userCacheEntry.getAuthorizedScopes()
                                                 .stream()
                                                 .anyMatch(s -> s.equals(scopeName));
                if (anyMatch) {
                    LOGGER.debug("Scopes are loaded from cache for auth scope key : {} ", scopeName);
                    return true;
                } else {
                    if (authScopeStore.authorize(scopeName, userCacheEntry.getUserGroups())) {
                        userCacheEntry.getAuthorizedScopes().add(scopeName);
                        return true;
                    } else {
                        return false;
                    }
                }
            } else {
                throw new BrokerAuthException("user id cannot be null.");
            }
        } catch (ExecutionException e) {
            throw new BrokerAuthServerException("Error occurred while retrieving authorizations from cache for"
                                                        + " scope name : " + scopeName, e);
        }
    }

    @Override
    public boolean authorize(String resourceType, String resourceName, String action, String userId)
            throws BrokerAuthException, BrokerAuthServerException, BrokerAuthNotFoundException {
        ResourceCacheKey resourceCacheKey = new ResourceCacheKey(resourceType, resourceName);
        try {
            if (userId != null) {
                UserCacheEntry userCacheEntry = userCache.get(userId);
                Set<String> authorizedActions =
                        userCacheEntry.getAuthorizedResourceActions().get(resourceCacheKey);
                boolean isAuthorize = Objects.nonNull(authorizedActions) &&
                        authorizedActions
                                .stream()
                                .anyMatch(s -> s.equals(action));
                if (isAuthorize) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("resourceName authorizations are loaded from cache for resourceType : " +
                                resourceType + " resourceName: " + resourceName);
                    }
                    return true;
                } else {
                    if (authResourceStore.authorize(resourceType, resourceName,
                                                    action, userId, userCacheEntry.getUserGroups())) {
                        if (Objects.isNull(authorizedActions)) {
                            authorizedActions = new HashSet<>();
                            userCacheEntry.getAuthorizedResourceActions().put(resourceCacheKey, authorizedActions);
                        }
                        authorizedActions.add(action);
                        return true;
                    } else {
                        return false;
                    }
                }
            } else {
                throw new BrokerAuthException("user id cannot be null.");
            }
        } catch (ExecutionException e) {
            throw new BrokerAuthException("Error occurred while retrieving authorizations from cache for "
                                                  + "resourceType : " + resourceType +
                                                  " resourceName: " + resourceName, e);
        }
    }

    @Override
    public AuthScopeStore getAuthScopeStore() {
        return authScopeStore;
    }

    @Override
    public void addProtectedResource(String resourceType, String resourceName, boolean durable, String owner)
            throws BrokerAuthServerException, BrokerAuthDuplicateException {
        authResourceStore.add(new AuthResource(resourceType, resourceName, durable, owner));
    }

    @Override
    public void deleteProtectedResource(String resourceType, String resourceName)
            throws BrokerAuthServerException, BrokerAuthNotFoundException {
        authResourceStore.delete(resourceType, resourceName);
    }

    private class UserCacheLoader extends CacheLoader<String, UserCacheEntry> {
        @Override
        public UserCacheEntry load(@Nonnull String userId) throws BrokerAuthException {
            UserCacheEntry userCacheEntry = new UserCacheEntry();
            userCacheEntry.setUserGroups(userStore.getUserGroupsList(userId));
            return userCacheEntry;
        }
    }
}
