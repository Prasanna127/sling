/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.extensions.junit.impl;

import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.extensions.junit.JUnitTestsManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Service
public class JUnitTestsManagerImpl implements BundleListener,JUnitTestsManager {
    private final Logger log = LoggerFactory.getLogger(getClass());
    public static final String TEST_PACKAGE_HEADER = "Test-Package";
    private BundleContext bundleContext;
    
    /** Symbolic names of bundles that changed state - if not empty, need
     *  to adjust the list of tests
     */
    private final List<String> changedBundles = new ArrayList<String>();
    
    /** List of (candidate) test classes, keyed by bundle so that we can
     *  update them easily when bundles come and go 
     */
    private final Map<String, List<String>> testClassesMap = new HashMap<String, List<String>>();

    private String getTestPackageName(Bundle b) {
        return (String)b.getHeaders().get(TEST_PACKAGE_HEADER);
    }
    
    protected void activate(ComponentContext ctx) {
        bundleContext = ctx.getBundleContext();
        bundleContext.addBundleListener(this);
        
        // Initially consider all bundles as "changed"
        for(Bundle b : bundleContext.getBundles()) {
            if(getTestPackageName(b) != null) {
                changedBundles.add(b.getSymbolicName());
                log.debug("Will look for test classes inside bundle {}", b.getSymbolicName());
            }
        }
    }
    
    protected void deactivate(ComponentContext ctx) {
        bundleContext.removeBundleListener(this);
        bundleContext = null;
        changedBundles.clear();
    }
    
    /** Called when a bundle changes state */
    public void bundleChanged(BundleEvent event) {
        // Only consider bundles which contain tests
        final Bundle b = event.getBundle();
        if(getTestPackageName(b) == null) {
            log.debug("Bundle {} does not have {} header, ignored", 
                    b.getSymbolicName(), TEST_PACKAGE_HEADER);
            return;
        }
        synchronized (changedBundles) {
            log.debug("Got BundleEvent for Bundle {}, will rebuild its lists of tests");
            changedBundles.add(b.getSymbolicName());
        }
    }
    
    /** Update testClasses if bundle changes require it */
    private void maybeUpdateTestClasses() {
        if(changedBundles.isEmpty()) {
            return;
        }

        // Get the list of bundles that have changed
        final List<String> bundlesToUpdate = new ArrayList<String>();
        synchronized (changedBundles) {
            bundlesToUpdate.addAll(changedBundles);
            changedBundles.clear();
        }
        
        // Remove test classes that belong to changed bundles
        for(String symbolicName : bundlesToUpdate) {
            testClassesMap.remove(symbolicName);
        }
        
        // Get test classes from bundles that are in our list
        for(Bundle b : bundleContext.getBundles()) {
            if(bundlesToUpdate.contains(b.getSymbolicName())) {
                final List<String> testClasses = getTestClasses(b);
                if(testClasses != null) {
                    testClassesMap.put(b.getSymbolicName(), testClasses);
                    log.debug("{} test classes found in bundle {}, added to our list", 
                            testClasses.size(), b.getSymbolicName());
                } else {
                    log.debug("No test classes found in bundle {}", b.getSymbolicName());
                }
            }
        }
    }

    /** @inheritDoc */
    public List<String> getTestClasses() {
        maybeUpdateTestClasses();
        final List<String> result = new ArrayList<String>();
        for(List<String> list : testClassesMap.values()) {
            result.addAll(list);
        }
        return result;
    }

    /** Get test classes that bundle b provides (as done in Felix/Sigil) */
    private List<String> getTestClasses(Bundle b) {
        final List<String> result = new ArrayList<String>();
        final String testPackage = getTestPackageName(b);
        
        if(testPackage == null) {
            log.info("Bundle {} does not have {} header, not looking for test classes", TEST_PACKAGE_HEADER);
        } else if(Bundle.ACTIVE != b.getState()) {
            log.info("Bundle {} is not active, no test classes considered", b.getSymbolicName());
        } else {
            @SuppressWarnings("unchecked")
            Enumeration<URL> classUrls = b.findEntries("", "*.class", true);
            while (classUrls.hasMoreElements()) {
                URL url = classUrls.nextElement();
                final String name = toClassName(url);
                if(name.startsWith(testPackage)) {
                    result.add(name);
                } else {
                    log.debug("Class {} is not in test package {} of bundle {}, ignored",
                            new Object[] { name, testPackage, b.getSymbolicName() });
                }
            }
            log.info("{} test classes found in bundle {}", result.size(), b.getSymbolicName());
        }
        
        return result;
    }
    
    /** Convert class URL to class name */
    private String toClassName(URL url) {
        final String f = url.getFile();
        final String cn = f.substring(1, f.length() - ".class".length());
        return cn.replace('/', '.');
    }

    /** Find bundle by symbolic name */
    private Bundle findBundle(String symbolicName) {
        for(Bundle b : bundleContext.getBundles()) {
            if(b.getSymbolicName().equals(symbolicName)) {
                return b;
            }
        }
        return null;
    }
    
    /** @inheritDoc */
    public Class<?> getTestClass(String className) throws ClassNotFoundException {
        // Find the bundle to which the class belongs
        Bundle b = null;
        for(Map.Entry<String, List<String>> e : testClassesMap.entrySet()) {
            if(e.getValue().contains(className)) {
                b = findBundle(e.getKey());
                break;
            }
        }
        
        if(b == null) {
            throw new IllegalArgumentException("No Bundle found that supplies test class " + className);
        }
        return b.loadClass(className);
    }
}