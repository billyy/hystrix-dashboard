/**
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.dashboard.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.*;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;

import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.scheme.Scheme;

import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;

import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.http.conn.scheme.SchemeRegistry;

/**
 * Proxy an EventStream request (data.stream via proxy.stream) since EventStream does not yet support CORS (https://bugs.webkit.org/show_bug.cgi?id=61862)
 * so that a UI can request a stream from a different server.
 */
public class ProxyStreamServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(ProxyStreamServlet.class);

    public ProxyStreamServlet() {
        super();
    }

    /**
     * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String origin = request.getParameter("origin");
        String authorization = request.getParameter("authorization");
        if (origin == null) {
            response.setStatus(500);
            response.getWriter().println("Required parameter 'origin' missing. Example: 107.20.175.135:7001");
            return;
        }
        origin = origin.trim();

        HttpGet httpget = null;
        InputStream is = null;
        boolean hasFirstParameter = false;
        StringBuilder url = new StringBuilder();
        if (!origin.startsWith("http")) {
            url.append("http://");
        }
        url.append(origin);
        if (origin.contains("?")) {
            hasFirstParameter = true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String[]> params = request.getParameterMap();
        for (String key : params.keySet()) {
            if (!key.equals("origin") && !key.equals("authorization")) {
                String[] values = params.get(key);
                String value = values[0].trim();
                if (hasFirstParameter) {
                    url.append("&");
                } else {
                    url.append("?");
                    hasFirstParameter = true;
                }
                url.append(key).append("=").append(value);
            }
        }
        String proxyUrl = url.toString();
        logger.info("\n\nProxy opening connection to: {}\n\n", proxyUrl);
        try {
            httpget = new HttpGet(proxyUrl);
            if (authorization != null) {
                httpget.addHeader("Authorization", authorization);
            }
            HttpClient client = ProxyConnectionManager.httpClient;
            HttpResponse httpResponse = client.execute(httpget);
            int statusCode = httpResponse.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_OK) {
                // writeTo swallows exceptions and never quits even if outputstream is throwing IOExceptions (such as broken pipe) ... since the inputstream is infinite
                // httpResponse.getEntity().writeTo(new OutputStreamWrapper(response.getOutputStream()));
                // so I copy it manually ...
                is = httpResponse.getEntity().getContent();

                // set headers
                for (Header header : httpResponse.getAllHeaders()) {
                    if (!HttpHeaders.TRANSFER_ENCODING.equals(header.getName())) {
                        response.addHeader(header.getName(), header.getValue());
                    }
                }

                // copy data from source to response
                OutputStream os = response.getOutputStream();
                int b = -1;
                while ((b = is.read()) != -1) {
                    try {
                        os.write(b);
                        if (b == 10 /** flush buffer on line feed */) {
                            os.flush();
                        }
                    } catch (Exception e) {
                        if (e.getClass().getSimpleName().equalsIgnoreCase("ClientAbortException")) {
                            // don't throw an exception as this means the user closed the connection
                            logger.debug("Connection closed by client. Will stop proxying ...");
                            // break out of the while loop
                            break;
                        } else {
                            // received unknown error while writing so throw an exception
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error proxying request: " + url, e);
        } finally {
            if (httpget != null) {
                try {
                    httpget.abort();
                } catch (Exception e) {
                    logger.error("failed aborting proxy connection.", e);
                }
            }

            // httpget.abort() MUST be called first otherwise is.close() hangs (because data is still streaming?)
            if (is != null) {
                // this should already be closed by httpget.abort() above
                try {
                    is.close();
                } catch (Exception e) {
                    // e.printStackTrace();
                }
            }
        }
    }
    public static class ProxyConnectionManager {
        public final static HttpClient httpClient = createHttpClientThatAcceptsUntrustedCerts();

        private static final HttpClient createHttpClientThatAcceptsUntrustedCerts() {
            HttpClientBuilder b = HttpClientBuilder.create();
            b.setDefaultRequestConfig(RequestConfig.custom().
                    setConnectTimeout(5000).
                    setSocketTimeout(10000).
                    build());

            // setup a Trust Strategy that allows all certificates.
            //
            try {
//                    SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, new TrustStrategy() {
//                        public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
//                            return true;
//                        }
//                    }).build();
                // This uses a more specific, self-signed trust strategy instead of "trust all" above.
                SSLContext sslContext = new SSLContextBuilder().
                        loadTrustMaterial(TrustSelfSignedStrategy.INSTANCE).
                        build();
                b.setSslcontext(sslContext);

                // don't check Hostnames, either.
                //      -- use SSLConnectionSocketFactory.getDefaultHostnameVerifier(), if you don't want to weaken, could be done using an Environment property switch to selectively control behavior
                HostnameVerifier hostnameVerifier = SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER;

                Set<String> enabledProtocols = new HashSet<String>();

                for (String s : sslContext.getDefaultSSLParameters().getProtocols()) {
                    System.out.println(s);
                    if (s.equals("SSLv3") || s.equals("SSLv2Hello")) {
                        logger.info("REMOVE PROTOCOL " + s);
                        continue;
                    }
                    enabledProtocols.add(s);
                }
                // here's the special part:
                //      -- need to create an SSL Socket Factory, to use our weakened "trust strategy";
                //      -- and create a Registry, to register it.
                //
                SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext,
                                                              enabledProtocols.toArray(new String[enabledProtocols.size()]),
                                          null,
                                                              hostnameVerifier);
                Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.getSocketFactory())
                        .register("https", sslSocketFactory)
                        .build();

                // now, we create connection-manager using our Registry.
                //      -- allows multi-threaded use
                PoolingHttpClientConnectionManager connMgr = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
                connMgr.setDefaultMaxPerRoute(400);
                connMgr.setMaxTotal(400);
                b.setConnectionManager(connMgr);

            } catch (Exception e) {
                // Do Nothing... for now
                logger.error("Unable to create SSLContext", e);
            }

            // finally, build the HttpClient;
            //      -- done!
            HttpClient client = b.build();
            return client;
        }
    }
 /*   private static class ProxyConnectionManager {
        private final static PoolingClientConnectionManager threadSafeConnectionManager = new PoolingClientConnectionManager(ignoreSSL());
        private final static HttpClient httpClient = new DefaultHttpClient(threadSafeConnectionManager);

        static {

            logger.debug("Initialize ProxyConnectionManager");
            *//* common settings *//*
            HttpParams httpParams = httpClient.getParams();
            HttpConnectionParams.setConnectionTimeout(httpParams, 5000);
            HttpConnectionParams.setSoTimeout(httpParams, 10000);

            *//* number of connections to allow *//*
            threadSafeConnectionManager.setDefaultMaxPerRoute(400);
            threadSafeConnectionManager.setMaxTotal(400);
        }

        private static SchemeRegistry ignoreSSL() {
            SSLContext sslContext = null;
            try {
                sslContext = SSLContext.getInstance("TLSv1");
                // set up a TrustManager that trusts everything
                sslContext.init(null, new TrustManager[] { new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        System.out.println("getAcceptedIssuers =============");
                        return null;
                    }

                    public void checkClientTrusted(X509Certificate[] certs,
                                                   String authType) {
                        System.out.println("checkClientTrusted =============");
                    }

                    public void checkServerTrusted(X509Certificate[] certs,
                                                   String authType) {
                        System.out.println("checkServerTrusted =============");
                    }
                } }, new SecureRandom());
            } catch(Exception e) {}


            SSLSocketFactory sf = new SSLSocketFactory(sslContext);
            sf.setHostnameVerifier(new CustomHostnameVerifier());

            Scheme httpsScheme = new Scheme("https", 8490, sf);

            SchemeRegistry schemeRegistry = new SchemeRegistry();
            schemeRegistry.register(httpsScheme);

            return schemeRegistry;
        }

        private static class CustomHostnameVerifier implements org.apache.http.conn.ssl.X509HostnameVerifier {

            @Override
            public boolean verify(String host, SSLSession session) {
                HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
                return hv.verify(host, session);
            }

            @Override
            public void verify(String host, SSLSocket ssl) throws IOException {
            }

            @Override
            public void verify(String host, X509Certificate cert) throws SSLException {

            }

            @Override
            public void verify(String host, String[] cns, String[] subjectAlts) throws SSLException {

            }
        }
    } */
}
