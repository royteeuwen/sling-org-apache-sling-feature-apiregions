/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.feature.apiregions.impl.console;

import org.apache.felix.webconsole.AbstractWebConsolePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;


//@Component(service = Servlet.class,
//        property = {Constants.SERVICE_DESCRIPTION + "=Apache Sling API Region Web Console Plugin",
//                WebConsoleConstants.PLUGIN_LABEL + "=" + RegionWebconsolePlugin.LABEL,
//                WebConsoleConstants.PLUGIN_TITLE + "=" + RegionWebconsolePlugin.TITLE,
//                WebConsoleConstants.PLUGIN_CATEGORY + "=Sling"})
@SuppressWarnings("serial")
public class RegionWebconsolePlugin extends AbstractWebConsolePlugin {

    public static final String LABEL = "slingapiregions";
    public static final String TITLE = "API Regions";

    private static final Logger log = LoggerFactory.getLogger(RegionWebconsolePlugin.class);

    @Override
    public String getLabel() {
        return LABEL;
    }

    @Override
    public String getTitle() {
        return TITLE;
    }

    @Override
    protected void renderContent(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        final PrintWriter pw = response.getWriter();

        info(pw, "Configurations are managed in the resource tree. Use this tool to test configuration resolutions.");

        pw.println("<br/>");

        printResolutionTestTool(request, pw);
    }

    private String getParameter(final HttpServletRequest request, final String name, final String defaultValue) {
        String value = request.getParameter(name);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return defaultValue;
    }

    private void printResolutionTestTool(HttpServletRequest request, PrintWriter pw) {
        final String path = this.getParameter(request, "path", "/content");
        String configNameOther = this.getParameter(request, "configNameOther", null);
        String configName = this.getParameter(request, "configName", null);
    }

    private void info(PrintWriter pw, String text) {
        pw.print("<p class='statline ui-state-highlight'>");
        pw.print(text);
        pw.println("</p>");
    }


}
