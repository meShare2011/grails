/*
 * Copyright 2004-2005 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.web.metaclass;

import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import groovy.lang.MissingMethodException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.commons.ControllerArtefactHandler;
import org.codehaus.groovy.grails.commons.GrailsClassUtils;
import org.codehaus.groovy.grails.commons.GrailsControllerClass;
import org.codehaus.groovy.grails.commons.metaclass.AbstractDynamicMethodInvocation;
import org.codehaus.groovy.grails.scaffolding.GrailsScaffolder;
import org.codehaus.groovy.grails.web.mapping.UrlMapping;
import org.codehaus.groovy.grails.web.mapping.UrlMappingsHolder;
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes;
import org.codehaus.groovy.grails.web.servlet.GrailsHttpServletResponse;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest;
import org.codehaus.groovy.grails.web.servlet.mvc.exceptions.ControllerExecutionException;
import org.springframework.context.ApplicationContext;
import org.springframework.validation.Errors;
import org.springframework.web.context.request.RequestContextHolder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Implements the "redirect" Controller method for action redirection
 * 
 * @author Graeme Rocher
 * @since 0.2
 * 
 * Created Oct 27, 2005
 */
public class RedirectDynamicMethod extends AbstractDynamicMethodInvocation {

	private static final String SCAFFOLDER = "Scaffolder";
    public static final String METHOD_SIGNATURE = "redirect";
    public static final Pattern METHOD_PATTERN = Pattern.compile('^'+METHOD_SIGNATURE+'$');
    public static final String ARGUMENT_URI = "uri";
    public static final String ARGUMENT_URL = "url";
    public static final String ARGUMENT_CONTROLLER = "controller";
    public static final String ARGUMENT_ACTION = "action";
    public static final String ARGUMENT_ID = "id";
    public static final String ARGUMENT_PARAMS = "params";
    public static final String ARGUMENT_ERRORS = "errors";

    private static final Log LOG = LogFactory.getLog(RedirectDynamicMethod.class);
    private static final char SLASH = '/';
    private UrlMappingsHolder urlMappingsHolder;

    public RedirectDynamicMethod(ApplicationContext applicationContext) {
        super(METHOD_PATTERN);
        if(applicationContext.containsBean(UrlMappingsHolder.BEAN_ID))
            this.urlMappingsHolder = (UrlMappingsHolder)applicationContext.getBean(UrlMappingsHolder.BEAN_ID);
    }

    public Object invoke(Object target, String methodName, Object[] arguments) {
        if(arguments.length == 0)
            throw new MissingMethodException(METHOD_SIGNATURE,target.getClass(),arguments);

        Map argMap = arguments[0] instanceof Map ? (Map)arguments[0] : Collections.EMPTY_MAP;
        if(argMap.size() == 0){
            throw new MissingMethodException(METHOD_SIGNATURE,target.getClass(),arguments);
        }


        Object actionRef = argMap.get(ARGUMENT_ACTION);
        String controllerName = argMap.containsKey(ARGUMENT_CONTROLLER) ? argMap.get(ARGUMENT_CONTROLLER).toString() : null;
        Object id = argMap.get(ARGUMENT_ID);
        Object uri = argMap.get(ARGUMENT_URI);
        String url = argMap.containsKey(ARGUMENT_URL) ? argMap.get(ARGUMENT_URL).toString() : null;
        Map params = (Map)argMap.get(ARGUMENT_PARAMS);
        Errors errors = (Errors)argMap.get(ARGUMENT_ERRORS);
        GroovyObject controller = (GroovyObject)target;

        // if there are errors add it to the list of errors
        Errors controllerErrors = (Errors)controller.getProperty( ControllerDynamicMethods.ERRORS_PROPERTY );
        if(controllerErrors != null) {
            controllerErrors.addAllErrors(errors);
        }
        else {
            controller.setProperty( ControllerDynamicMethods.ERRORS_PROPERTY, errors);
        }

        String actualUri;
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes();
        
        GrailsApplicationAttributes attrs = webRequest.getAttributes();
        HttpServletRequest request = webRequest.getCurrentRequest();
        GrailsHttpServletResponse response = webRequest.getCurrentResponse();
        StringBuffer actualUriBuf = new StringBuffer(attrs.getApplicationUri(request));
        if(uri != null) {
            actualUri = attrs.getApplicationUri(request) + uri.toString();
        }
        else if(url != null) {
            actualUri = url;
        }
        else {            
            String actionName = establishActionName(actionRef, target, webRequest);

            UrlMapping urlMapping = null;
            if(urlMappingsHolder!=null)
                urlMapping = urlMappingsHolder.getReverseMapping(controllerName, actionName, params);
            
            if(urlMapping != null) {
               String mappedUrl = urlMapping.createURL(params);
               actualUriBuf.append(mappedUrl);
            }
            else {
                if(actionName != null) {

                    if(actionName.indexOf(SLASH) > -1) {
                          actualUriBuf.append(actionName);
                    }
                    else {
                        if(controllerName != null) {
                            appendUrlToken(actualUriBuf, controllerName);
                        }
                        else {
                            actualUriBuf.append(attrs.getControllerUri(request));
                        }
                    }
                    appendUrlToken(actualUriBuf, actionName);
                }
                else {
                    throw new ControllerExecutionException("Action not found in redirect for name ["+actionName+"]");
                }
                if(id != null) {
                    appendUrlToken(actualUriBuf, id);
                }
                if(params != null) {
                    appendRequestParams(actualUriBuf, params, request);
                }
            }
            actualUri = actualUriBuf.toString();

        }

        return redirectResponse(actualUri, response);
    }

    /*
     * Appends all the requeset parameters to the URI buffer
     */
    private void appendRequestParams(StringBuffer actualUriBuf, Map params, HttpServletRequest request) {
        if(params.size() > 0)
            actualUriBuf.append('?');
        
        for (Iterator i = params.keySet().iterator(); i.hasNext();) {
            Object name = i.next();
            if(name.equals(GrailsControllerClass.CONTROLLER) || name.equals(GrailsControllerClass.ACTION))
                continue;
            Object value = params.get(name);
            appendRequestParam(actualUriBuf, name, value,request);
            if(i.hasNext())
                actualUriBuf.append('&');
        }
    }

    /*
     * Redirects the response the the given URI
     */
    private Object redirectResponse(String actualUri, HttpServletResponse response) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("Dynamic method [redirect] forwarding request to ["+actualUri +"]");
        }

        try {

            if(LOG.isDebugEnabled()) {
                LOG.debug("Executing redirect with response ["+response+"]");
            }
            String redirectUrl = response.encodeRedirectURL(actualUri);
            response.sendRedirect(redirectUrl);

        } catch (IOException e) {
            throw new ControllerExecutionException("Error redirecting request for url ["+actualUri +"]: " + e.getMessage(),e);
        }
        return null;
    }

    /*
     * Appends a request parameters for the given aname and value
     */
    private void appendRequestParam(StringBuffer actualUriBuf, Object name, Object value,HttpServletRequest request) {
        if (value==null)
            value = "";
        
        try {
            actualUriBuf.append(URLEncoder.encode(name.toString(),request.getCharacterEncoding()))
                     .append('=')
                     .append(URLEncoder.encode(value.toString(),request.getCharacterEncoding()));
        } catch (UnsupportedEncodingException ex) {
            throw new ControllerExecutionException("Error redirecting request for url ["+name+":"+value +"]: " + ex.getMessage(),ex);
        }
    }

    /*
     * Appends a URL token to the buffer
     */
    private void appendUrlToken(StringBuffer actualUriBuf, Object token) {
        actualUriBuf.append(SLASH).append(token);
    }
    /*
     * Figures out the action name from the specified action reference (either a string or closure)
     */
    private String establishActionName(Object actionRef, Object target, GrailsWebRequest webRequest) {
        String actionName = "";
        if(actionRef instanceof String) {
           actionName = (String)actionRef;
        }
        else if(actionRef instanceof Closure) {
            Closure c = (Closure)actionRef;
            PropertyDescriptor prop = GrailsClassUtils.getPropertyDescriptorForValue(target,c);
            if(prop != null) {
                actionName = prop.getName();
            }
            else {
                GrailsScaffolder scaffolder = getScaffolderForController(target.getClass().getName(), webRequest);
                if(scaffolder != null) {
                        actionName = scaffolder.getActionName(c);
                }
            }
        }
        return actionName;
    }

    public GrailsScaffolder getScaffolderForController(String controllerName, GrailsWebRequest webRequest) {
    	GrailsApplicationAttributes attributes = webRequest.getAttributes();
		GrailsControllerClass controllerClass = (GrailsControllerClass) attributes.getGrailsApplication().getArtefact(
            ControllerArtefactHandler.TYPE, controllerName);
        return (GrailsScaffolder)attributes
        							.getApplicationContext()
        							.getBean(controllerClass.getFullName() + SCAFFOLDER );
    }    

}
