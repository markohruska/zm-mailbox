/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2007, 2008, 2009, 2010 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.common.soap;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.dom4j.DocumentException;
import org.dom4j.Namespace;
import org.dom4j.QName;

import com.google.common.base.Objects;
import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element.ElementFactory;
import com.zimbra.common.soap.Element.JSONElement;
import com.zimbra.common.soap.Element.XMLElement;
import com.zimbra.common.util.ByteUtil;
import com.zimbra.common.util.CliUtil;
import com.zimbra.common.util.StringUtil;

public class SoapCommandUtil implements SoapTransport.DebugListener {

    private static final Map<String, Namespace> sTypeToNamespace =
        new TreeMap<String, Namespace>();

    private static final String DEFAULT_ADMIN_URL = String.format("https://%s:%d/service/admin/soap",
        LC.zimbra_zmprov_default_soap_server.value(),
        LC.zimbra_admin_service_port.intValue());
    private static final String DEFAULT_URL = "http://" + LC.zimbra_zmprov_default_soap_server.value() + "/service/soap";

    private static final String LO_HELP = "help";
    private static final String LO_MAILBOX = "mailbox";
    private static final String LO_AUTH = "auth";
    private static final String LO_ADMIN_PRIV = "admin-priv";
    private static final String LO_ADMIN = "admin";
    private static final String LO_PASSWORD = "password";
    private static final String LO_PASSFILE = "passfile";
    private static final String LO_URL = "url";
    private static final String LO_ZADMIN = "zadmin";
    private static final String LO_VERBOSE = "verbose";
    private static final String LO_VERY_VERBOSE = "very-verbose";
    private static final String LO_NO_OP = "no-op";
    private static final String LO_SELECT = "select";
    private static final String LO_JSON = "json";
    private static final String LO_FILE = "file";
    private static final String LO_TYPE = "type";
    private static final String LO_USE_SESSION = "use-session";

    private static final String TYPE_MAIL = "mail";
    private static final String TYPE_ADMIN = "admin";
    private static final String TYPE_ACCOUNT = "account";
    private static final String TYPE_IM = "im";
    private static final String TYPE_MOBILE = "mobile";
    private static final String TYPE_OFFLINE = "offline";
    
    static {
        // Namespaces
        sTypeToNamespace.put(TYPE_MAIL, Namespace.get("urn:zimbraMail"));
        sTypeToNamespace.put(TYPE_ADMIN, Namespace.get("urn:zimbraAdmin"));
        sTypeToNamespace.put(TYPE_ACCOUNT, Namespace.get("urn:zimbraAccount"));
        sTypeToNamespace.put(TYPE_IM, Namespace.get("urn:zimbraIM"));
        sTypeToNamespace.put(TYPE_MOBILE, Namespace.get("urn:zimbraSync"));
        sTypeToNamespace.put(TYPE_OFFLINE, Namespace.get("urn:zimbraOffline"));
    }
    
    private Options mOptions = new Options();
    private String mUrl;
    private String mType;
    private Namespace mNamespace;
    private String mAuthAccountName;
    private String mAdminAccountName;
    private String mTargetAccountName;
    private String mPassword;
    private String[] mPaths;
    private String mAuthToken;
    private String mSessionId;
    private SoapHttpTransport mTransport;
    private boolean mVerbose = false;
    private boolean mVeryVerbose = false;
    private boolean mUseSession = false;
    private boolean mNoOp = false;
    private String mSelect;
    private boolean mUseJson = false;
    private String mFilePath;
    private ElementFactory mFactory;
    private PrintStream mOut;

    SoapCommandUtil() {
        // Initialize options.
        mOptions.addOption(new Option("h", LO_HELP, false, "Display this help message."));

        Option opt = new Option("m", LO_MAILBOX, true, "Send mail and account requests to this account.  " +
            "Also used for authentication if --auth, -a and -z are not specified.");
        opt.setArgName("account-name");
        mOptions.addOption(opt);

        opt = new Option(null, LO_AUTH, true, "Account name to authenticate as.  Defaults to account in -m.");
        opt.setArgName("account-name");
        mOptions.addOption(opt);

        mOptions.addOption(new Option("A", LO_ADMIN_PRIV, false, "Execute requests with admin privileges."));

        opt = new Option("a", LO_ADMIN, true, "Admin account name to authenticate as.");
        opt.setArgName("account-name");
        mOptions.addOption(opt);

        opt = new Option("p", LO_PASSWORD, true, "Password.");
        opt.setArgName("password");
        mOptions.addOption(opt);
        
        opt = new Option("P", LO_PASSFILE, true, "Read password from file.");
        opt.setArgName("path");
        mOptions.addOption(opt);
        
        opt = new Option("u", LO_URL, true, "SOAP service URL, usually " +
                "http[s]://host:port/service/soap or https://host:port/service/admin/soap.");
        opt.setArgName("url");
        mOptions.addOption(opt);
        
        mOptions.addOption(new Option("z", LO_ZADMIN, false,
            "Authenticate with zimbra admin name/password from localconfig."));
        mOptions.addOption(new Option("v", LO_VERBOSE, false, 
            "Print the request."));
        mOptions.addOption(new Option("vv", LO_VERY_VERBOSE, false,
            "Print URLs and all requests and responses with envelopes."));
        mOptions.addOption(new Option("n", LO_NO_OP, false, "Print the SOAP request only.  Don't send it."));
        
        opt = new Option(null, LO_SELECT, true, "Select an element or attribute from the response.");
        opt.setArgName("xpath");
        mOptions.addOption(opt);
        
        mOptions.addOption(new Option(null, LO_JSON, false, "Use JSON instead of XML."));
        
        opt = new Option("f", LO_FILE, true, "Read request from file.");
        opt.setArgName("path");
        mOptions.addOption(opt);
        
        String types = StringUtil.join(",", sTypeToNamespace.keySet());
        opt = new Option("t", LO_TYPE, true,
            "SOAP request type: " + types + ".  Default is admin, or mail if -m is specified.");
        opt.setArgName("type");
        mOptions.addOption(opt);
        
        mOptions.addOption(new Option(null, LO_USE_SESSION, false, "Use a SOAP session."));

        try {
            mOut = new PrintStream(System.out, true, "utf-8");
        } catch (UnsupportedEncodingException e) {}
    }

    private void usage(String errorMsg) {
        int exitStatus = 0;
        
        if (errorMsg != null) {
            System.err.println(errorMsg);
            exitStatus = 1;
        }
        HelpFormatter format = new HelpFormatter();
        format.printHelp(new PrintWriter(System.err, true), 80,
            "zmsoap [options] xpath1 [xpath2 xpath3 ...]", null, mOptions, 2, 2,
            "Element paths roughly follow XPath syntax.  " +
            "The path of each subsequent element is relative to the previous one.  " +
            "To navigate up the element tree, use \"../\" in the path.  " +
            "To specify attributes on the current element, use one or more @attr=val " +
            "arguments.  To specify element text, use \"path/to/element=text\".\n" +
            "Example: zmsoap -z GetAccountInfoRequest/account=user1 @by=name");
            System.exit(exitStatus);
    }

    
    private void parseCommandLine(String[] args)
    throws ParseException {
        // Parse command line
        GnuParser parser = new GnuParser();
        CommandLine cl = parser.parse(mOptions, args);
        
        if (CliUtil.hasOption(cl, LO_HELP)) {
            usage(null);
        }
        
        // Set member variables
        String val = CliUtil.getOptionValue(cl, LO_PASSFILE);
        if (!StringUtil.isNullOrEmpty(val)) {
            String path = CliUtil.getOptionValue(cl, LO_PASSFILE);
            try {
                mPassword = StringUtil.readSingleLineFromFile(path);
            } catch (IOException e) {
                usage("Cannot read password from file: " + e.getMessage());
            }
        }
        if (CliUtil.hasOption(cl, LO_PASSWORD)) {
            mPassword = CliUtil.getOptionValue(cl, LO_PASSWORD);
        }
        mAdminAccountName = CliUtil.getOptionValue(cl, LO_ADMIN);

        if (!CliUtil.hasOption(cl, LO_ADMIN) && CliUtil.hasOption(cl, LO_ZADMIN)) {
            mAdminAccountName = LC.zimbra_ldap_user.value();
            if (!CliUtil.hasOption(cl, LO_PASSWORD)) {
                mPassword = LC.zimbra_ldap_password.value();
            }
        }

        mTargetAccountName = CliUtil.getOptionValue(cl, LO_MAILBOX);
        if (CliUtil.hasOption(cl, LO_ADMIN_PRIV) && CliUtil.hasOption(cl, LO_AUTH)) {
            usage("Cannot combine --auth and -A.");
        }
        mAuthAccountName = CliUtil.getOptionValue(cl, LO_AUTH);
        if (StringUtil.isNullOrEmpty(mAuthAccountName) && !CliUtil.hasOption(cl, LO_ADMIN_PRIV)) {
            mAuthAccountName = mTargetAccountName;
        }
        if (StringUtil.isNullOrEmpty(mAuthAccountName) && StringUtil.isNullOrEmpty(mAdminAccountName)) {
            usage("Authentication account not specified.");
        }
        
        if (CliUtil.hasOption(cl, LO_TYPE)) {
            mType = CliUtil.getOptionValue(cl, LO_TYPE);
            if (!sTypeToNamespace.containsKey(mType)) {
                usage("Invalid type: " + mType);
            }
            if ((mType.equals(TYPE_MAIL) || mType.equals(TYPE_ACCOUNT) || mType.equals(TYPE_IM)) &&
                StringUtil.isNullOrEmpty(mTargetAccountName)) {
                usage("Mailbox must be specified for mail, account, and im requests.");
            }
        } else {
            if (!StringUtil.isNullOrEmpty(mTargetAccountName)) {
                mType = TYPE_MAIL;
            } else {
                mType = "admin";
            }
        }
        mNamespace = sTypeToNamespace.get(mType);
        
        mUrl = CliUtil.getOptionValue(cl, LO_URL);
        if (StringUtil.isNullOrEmpty(mUrl)) {
            if (!StringUtil.isNullOrEmpty(mAdminAccountName)) {
                mUrl = DEFAULT_ADMIN_URL;  
            } else {
                mUrl = DEFAULT_URL;
            }
        }

        mVeryVerbose = CliUtil.hasOption(cl, LO_VERY_VERBOSE);
        mVerbose = CliUtil.hasOption(cl, LO_VERBOSE);
        
        mPaths = cl.getArgs();
        mNoOp = CliUtil.hasOption(cl, LO_NO_OP);
        mSelect = CliUtil.getOptionValue(cl, LO_SELECT);
        mUseJson = CliUtil.hasOption(cl, LO_JSON);
        mFilePath = CliUtil.getOptionValue(cl, LO_FILE);
        mFactory = (mUseJson ? JSONElement.mFactory : XMLElement.mFactory);
        mUseSession = CliUtil.hasOption(cl, LO_USE_SESSION);
    }
    
    private static final String[] XPATH_PASSWORD = new String[] { "Body", AdminConstants.AUTH_REQUEST.getName(), AdminConstants.E_PASSWORD };
    
    public void sendSoapMessage(Element envelope) {
        if (mVeryVerbose) {
            // Obscure password if this is an AuthRequest.
            Element passwordElement = envelope.getPathElement(XPATH_PASSWORD);
            String originalPassword = null;
            if (passwordElement != null) {
                originalPassword = passwordElement.getText();
                passwordElement.setText("***");
            }
            
            mOut.println(envelope.prettyPrint());
            
            if (passwordElement != null) {
                passwordElement.setText(originalPassword);
            }
        }
    }
    
    public void receiveSoapMessage(Element envelope) {
        if (mVeryVerbose) {
            mOut.println(envelope.prettyPrint());
        }
    }
    
    private void adminAuth()
    throws ServiceException, IOException {
        // Create auth element
        Element auth = mFactory.createElement(AdminConstants.AUTH_REQUEST);
        auth.addElement(AdminConstants.E_NAME).setText(mAdminAccountName);
        auth.addElement(AdminConstants.E_PASSWORD).setText(mPassword);
        
        // Authenticate and get auth token
        Element response = null;
        
        if (mVeryVerbose) {
            mOut.println("Sending admin auth request to " + mUrl);
        }
        
        response = getTransport().invoke(auth, false, !mUseSession, null);
        handleAuthResponse(response);
        
        // Do delegate auth if this is a mail or account service request
        if (!mType.equals(TYPE_ADMIN) && !StringUtil.isNullOrEmpty(mAuthAccountName)) {
            boolean nameIsUUID = StringUtil.isUUID(mAuthAccountName);

            Element getInfo = mFactory.createElement(AdminConstants.GET_ACCOUNT_INFO_REQUEST);
            Element account = getInfo.addElement(AccountConstants.E_ACCOUNT).setText(mAuthAccountName);
            account.addAttribute(AdminConstants.A_BY, nameIsUUID ? AdminConstants.BY_ID : AdminConstants.BY_NAME);
            if (mVeryVerbose) {
                mOut.println(getInfo.prettyPrint());
            }
            response = getTransport().invoke(getInfo, false, !mUseSession, null);
            String userServiceUrl = response.getElement(AdminConstants.E_SOAP_URL).getText();
            
            // Get delegate auth token
            Element delegateAuth = mFactory.createElement(AdminConstants.DELEGATE_AUTH_REQUEST);
            account = delegateAuth.addElement(AccountConstants.E_ACCOUNT).setText(mAuthAccountName);
            account.addAttribute(AdminConstants.A_BY, nameIsUUID ? AdminConstants.BY_ID : AdminConstants.BY_NAME);
            response = getTransport().invoke(delegateAuth, false, !mUseSession, null);
            handleAuthResponse(response);
            
            // Set URL for subsequent mail and account requests.
            mUrl = userServiceUrl;
        }
    }
    
    private SoapHttpTransport getTransport() {
        if (mTransport == null || !Objects.equal(mTransport.getURI(), mUrl)) {
            mTransport = new SoapHttpTransport(mUrl);
        }
        mTransport.setAuthToken(mAuthToken);
        mTransport.setSessionId(mSessionId);
        mTransport.setDebugListener(this);
        mTransport.setTimeout(0);
        return mTransport;
    }
    
    private void handleAuthResponse(Element authResponse)
    throws ServiceException {
        mAuthToken = authResponse.getAttribute(AccountConstants.E_AUTH_TOKEN);
        Element sessionEl = authResponse.getOptionalElement(HeaderConstants.E_SESSION);
        if (sessionEl != null) {
            mSessionId = sessionEl.getAttribute(HeaderConstants.A_ID);
        }
    }
    
    private void mailboxAuth()
    throws ServiceException, IOException {
        if (mVeryVerbose) {
            mOut.println("Sending auth request to " + mUrl);
        }

        // Create auth element
        Element auth = mFactory.createElement(AccountConstants.AUTH_REQUEST);
        Element account = auth.addElement(AccountConstants.E_ACCOUNT).setText(mAuthAccountName);
        account.addAttribute(AdminConstants.A_BY, StringUtil.isUUID(mAuthAccountName) ? AdminConstants.BY_ID : AdminConstants.BY_NAME);
        auth.addElement(AccountConstants.E_PASSWORD).setText(mPassword);

        // Authenticate and get auth token
        Element response = getTransport().invoke(auth, false, !mUseSession, null);
        handleAuthResponse(response);
    }
    
    private void run()
    throws ServiceException, IOException, DocumentException {
        // Assemble SOAP request.
        Element element = null;
        InputStream in = null;
        String location = null;
        if (mFilePath != null) {
            // Read from file.
            in = new FileInputStream(mFilePath);
            location = mFilePath;
        } else if (mPaths.length > 0) {
            // Build request from command line.
            for (String path : mPaths) {
                element = processPath(element, path);
            }
        } else if (System.in.available() > 0) {
            // Read from stdin.
            in = System.in;
            location = "stdin";
        }

        if (in != null) {
            try {
                if (mUseJson) {
                    element = Element.parseJSON(in);
                } else {
                    element = Element.parseXML(in);
                }
            } catch (IOException e) {
                System.err.format("Unable to read request from %s: %s.\n", location, e.getMessage());
                System.exit(1);
            } finally {
                ByteUtil.closeStream(in);
            }
        }
        
        // Find the root.
        Element request = element;
        if (request == null) {
            usage("No request element specified.");
        }
        while (request.getParent() != null) {
            request = request.getParent();
        }
        
        if (mNoOp) {
            mOut.println(request.prettyPrint());
            return;
        }

        // Authenticate
        if (mAdminAccountName != null) {
            adminAuth();
        } else {
            mailboxAuth();
        }
        
        // Send request and print response.
        if (!mType.equals(TYPE_ADMIN) && !StringUtil.isNullOrEmpty(mTargetAccountName) &&
            !StringUtil.equalIgnoreCase(mAuthAccountName, mTargetAccountName)) {
            getTransport().setTargetAcctName(mTargetAccountName);
        }
        Element response = null;
        
        if (mVeryVerbose) {
            System.out.println("Sending request to " + mUrl);
        }
        if (mVerbose) {
            System.out.println(request.prettyPrint());
        }
        
        response = getTransport().invoke(request, false, !mUseSession, null);

        // Select result.
        List<Element> results = null;
        String resultString = null;
        
        if (mSelect != null) {
            // Create bogus root element, to allow us to find the first element in the path. 
            Element root = response.getFactory().createElement("root");
            response.detach();
            root.addElement(response);
            
            String[] parts = mSelect.split("/");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1];
                if (lastPart.startsWith("@")) {
                    parts[parts.length - 1] = lastPart.substring(1);
                    resultString = root.getPathAttribute(parts);
                } else {
                    results = root.getPathElementList(parts);
                }
            }
        } else {
            results = new ArrayList<Element>();
            results.add(response);
        }
        
        if (!mVeryVerbose) { // Envelope was already printed if we're doing very verbose logging.
            if (resultString == null && results != null) {
                StringBuilder buf = new StringBuilder();
                boolean first = true;
                for (Element e : results) {
                    if (first) {
                        first = false; 
                    } else {
                        buf.append('\n');
                    }
                    buf.append(e.prettyPrint());
                }
                resultString = buf.toString();
            }
            if (resultString == null) {
                resultString = "";
            }
            mOut.println(resultString);
        }
    }
    
    private static Pattern PAT_PATH_AND_VALUE = Pattern.compile("([^=]*)=(.*)");

    /**
     * Processes a path that's relative to the given root.  The path
     * is in an XPath-like format:
     * 
     * <p><tt>element1/element2[/@attr][=value]</tt></p>
     * 
     * If a value is specified, it sets the text of the last element
     * or attribute in the path.
     * 
     * @param start <tt>Element</tt> that the path is relative to, or <tt>null</tt>
     * for the root
     * @param path an XPath-like path of elements and attributes
     */
    private Element processPath(Element start, String path) {
        String value = null;
        
        // Parse out value, if it's specified.
        Matcher m = PAT_PATH_AND_VALUE.matcher(path);
        if (m.matches()) {
            path = m.group(1);
            value = m.group(2);
        }
        
        // Find the first element.
        Element element = start;
        
        // Walk parts and implicitly create elements.
        String[] parts = path.split("/");
        String part = null;
        for (int i = 0; i < parts.length; i++) {
            part = parts[i];
            if (element == null) {
                QName name = QName.get(part, mNamespace);
                element = mFactory.createElement(name);
            } else if (part.equals("..")) {
                element = element.getParent();
            } else if (!(part.startsWith("@"))) {
                element = element.addElement(part);
            }
        }
        
        // Set either element text or attribute value
        if (value != null && part != null) {
            if (part.startsWith("@")) {
                String attrName = part.substring(1);
                element.addAttribute(attrName, value);
            } else {
                element.setText(value);
            }
        }
        return element;
    }
    
    private static String formatServiceException(ServiceException e) {
        Throwable cause = e.getCause();
        return "ERROR: " + e.getCode() + " (" + e.getMessage() + ")" + 
            (cause == null ? "" : " (cause: " + cause.getClass().getName() + " " + cause.getMessage() + ")");  
    }
    
    public static void main(String[] args) {
        CliUtil.toolSetup();
        SoapTransport.setDefaultUserAgent("zmsoap", null);
        SoapCommandUtil app = new SoapCommandUtil();
        try {
            app.parseCommandLine(args);
        } catch (ParseException e) {
            app.usage(e.getMessage());
        }
        
        try {
            app.run();
        } catch (ServiceException e) {
            System.err.println(formatServiceException(e));
            if (app.mVerbose) {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        } catch (Exception e) {
            if (app.mVerbose || app.mVeryVerbose) {
                e.printStackTrace(System.err);
            } else {
                System.err.println(e);
            }
        }
    }
}
