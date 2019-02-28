/**
 * The MIT License
 *
 * Copyright (c) 2009-2019 PrimeTek
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.primefaces.context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.faces.FacesException;
import javax.faces.context.FacesContext;
import javax.servlet.ServletContext;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import org.primefaces.cache.CacheProvider;
import org.primefaces.cache.DefaultCacheProvider;

import org.primefaces.config.PrimeConfiguration;
import org.primefaces.config.PrimeEnvironment;
import org.primefaces.util.Constants;
import org.primefaces.util.LangUtils;
import org.primefaces.util.Lazy;
import org.primefaces.virusscan.VirusScannerService;

/**
 * A {@link PrimeApplicationContext} is a contextual store for the current application.
 *
 * It can be accessed via:
 * <blockquote>
 * PrimeApplicationContext.getCurrentInstance(context)
 * </blockquote>
 */
public class PrimeApplicationContext {

    public static final String INSTANCE_KEY = PrimeApplicationContext.class.getName();

    private static final Logger LOGGER = Logger.getLogger(PrimeApplicationContext.class.getName());

    private PrimeEnvironment environment;
    private PrimeConfiguration config;
    private ClassLoader applicationClassLoader;
    private Map<Class<?>, Map<String, Object>> enumCacheMap;
    private Map<Class<?>, Map<String, Object>> constantsCacheMap;

    private Lazy<ValidatorFactory> validatorFactory;
    private Lazy<Validator> validator;
    private Lazy<CacheProvider> cacheProvider;
    private Lazy<VirusScannerService> virusScannerService;

    public PrimeApplicationContext(FacesContext facesContext) {
        environment = new PrimeEnvironment(facesContext);
        config = new PrimeConfiguration(facesContext, environment);

        enumCacheMap = new ConcurrentHashMap<>();
        constantsCacheMap = new ConcurrentHashMap<>();

        Object context = facesContext.getExternalContext().getContext();
        if (context != null) {
            try {
                // Reflectively call getClassLoader() on the context in order to be compatible with both the Portlet 3.0
                // API and the Servlet API without depending on the Portlet API directly.
                Method getClassLoaderMethod = context.getClass().getMethod("getClassLoader");

                if (getClassLoaderMethod != null) {
                    applicationClassLoader = (ClassLoader) getClassLoaderMethod.invoke(context);
                }
            }
            catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | AbstractMethodError |
                NoSuchMethodError | UnsupportedOperationException e) {
                // Do nothing.
            }
            catch (Throwable t) {
                LOGGER.log(Level.WARNING, "An unexpected Exception or Error was thrown when calling " +
                    "facesContext.getExternalContext().getContext().getClassLoader(). Falling back to " +
                    "Thread.currentThread().getContextClassLoader() instead.", t);
            }
        }

        // If the context is unavailable or this is a Portlet 2.0 environment, the ClassLoader cannot be obtained from
        // the context, so use Thread.currentThread().getContextClassLoader() to obtain the application ClassLoader
        // instead.
        if (applicationClassLoader == null) {
            applicationClassLoader = LangUtils.getContextClassLoader();
        }


        if (config.isBeanValidationEnabled()) {
            validatorFactory = new Lazy<ValidatorFactory>() {
                @Override
                protected ValidatorFactory initialize() {
                    return Validation.buildDefaultValidatorFactory();
                }
            };
            validator = new Lazy<Validator>() {
                @Override
                protected Validator initialize() {
                    return validatorFactory.get().getValidator();
                }
            };
        }

        virusScannerService = new Lazy<VirusScannerService>() {
            @Override
            protected VirusScannerService initialize() {
                return new VirusScannerService(applicationClassLoader);
            }
        };

        cacheProvider = new Lazy<CacheProvider>() {
            @Override
            protected CacheProvider initialize() {
                String cacheProviderConfigValue = FacesContext.getCurrentInstance().getExternalContext()
                        .getInitParameter(Constants.ContextParams.CACHE_PROVIDER);
                if (cacheProviderConfigValue == null) {
                    return new DefaultCacheProvider();
                }
                else {
                    try {
                        return (CacheProvider) LangUtils.loadClassForName(cacheProviderConfigValue).newInstance();
                    }
                    catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
                        throw new FacesException(ex);
                    }
                }
            }
        };
    }

    public static PrimeApplicationContext getCurrentInstance(FacesContext facesContext) {
        if (facesContext == null || facesContext.getExternalContext() == null) {
            return null;
        }

        PrimeApplicationContext applicationContext =
                (PrimeApplicationContext) facesContext.getExternalContext().getApplicationMap().get(INSTANCE_KEY);

        if (applicationContext == null) {
            applicationContext = new PrimeApplicationContext(facesContext);
            setCurrentInstance(applicationContext, facesContext);
        }

        return applicationContext;
    }

    public static PrimeApplicationContext getCurrentInstance(ServletContext context) {
        return (PrimeApplicationContext) context.getAttribute(INSTANCE_KEY);
    }

    public static void setCurrentInstance(final PrimeApplicationContext context, final FacesContext facesContext) {
        facesContext.getExternalContext().getApplicationMap().put(INSTANCE_KEY, context);

        if (facesContext.getExternalContext().getContext() instanceof ServletContext) {
            ((ServletContext) facesContext.getExternalContext().getContext()).setAttribute(INSTANCE_KEY, context);
        }
    }

    public PrimeEnvironment getEnvironment() {
        return environment;
    }

    public PrimeConfiguration getConfig() {
        return config;
    }

    public ValidatorFactory getValidatorFactory() {
        return validatorFactory.get();
    }

    public CacheProvider getCacheProvider() {
        return cacheProvider.get();
    }

    public Map<Class<?>, Map<String, Object>> getEnumCacheMap() {
        return enumCacheMap;
    }

    public Map<Class<?>, Map<String, Object>> getConstantsCacheMap() {
        return constantsCacheMap;
    }

    public Validator getValidator() {
        return validator.get();
    }

    public VirusScannerService getVirusScannerService() {
        return virusScannerService.get();
    }

    public void release() {
        if (environment != null && environment.isAtLeastBv11()) {
            if (validatorFactory != null && validatorFactory.isInitialized() && validatorFactory.get() != null) {
                validatorFactory.get().close();
            }
        }
    }
}
