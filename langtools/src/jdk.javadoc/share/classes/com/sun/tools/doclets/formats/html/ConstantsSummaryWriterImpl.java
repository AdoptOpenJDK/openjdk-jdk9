/*
 * Copyright (c) 2001, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.doclets.formats.html;

import java.io.*;
import java.util.*;

import com.sun.javadoc.*;
import com.sun.tools.doclets.formats.html.markup.*;
import com.sun.tools.doclets.internal.toolkit.*;
import com.sun.tools.doclets.internal.toolkit.util.*;

/**
 * Write the Constants Summary Page in HTML format.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Jamie Ho
 * @author Bhavesh Patel (Modified)
 * @since 1.4
 */
@Deprecated
public class ConstantsSummaryWriterImpl extends HtmlDocletWriter
        implements ConstantsSummaryWriter {

    /**
     * The configuration used in this run of the standard doclet.
     */
    ConfigurationImpl configuration;

    /**
     * The current class being documented.
     */
    private ClassDoc currentClassDoc;

    private final String constantsTableSummary;

    private final String[] constantsTableHeader;

    /**
     * The HTML tree for main tag.
     */
    private HtmlTree mainTree = HtmlTree.MAIN();

    /**
     * The HTML tree for constant values summary.
     */
    private HtmlTree summaryTree;

    /**
     * Construct a ConstantsSummaryWriter.
     * @param configuration the configuration used in this run
     *        of the standard doclet.
     */
    public ConstantsSummaryWriterImpl(ConfigurationImpl configuration)
            throws IOException {
        super(configuration, DocPaths.CONSTANT_VALUES);
        this.configuration = configuration;
        constantsTableSummary = configuration.getText("doclet.Constants_Table_Summary",
                configuration.getText("doclet.Constants_Summary"));
        constantsTableHeader = new String[] {
            getModifierTypeHeader(),
            configuration.getText("doclet.ConstantField"),
            configuration.getText("doclet.Value")
        };
    }

    /**
     * {@inheritDoc}
     */
    public Content getHeader() {
        String label = configuration.getText("doclet.Constants_Summary");
        HtmlTree bodyTree = getBody(true, getWindowTitle(label));
        HtmlTree htmlTree = (configuration.allowTag(HtmlTag.HEADER))
                ? HtmlTree.HEADER()
                : bodyTree;
        addTop(htmlTree);
        addNavLinks(true, htmlTree);
        if (configuration.allowTag(HtmlTag.HEADER)) {
            bodyTree.addContent(htmlTree);
        }
        return bodyTree;
    }

    /**
     * {@inheritDoc}
     */
    public Content getContentsHeader() {
        return new HtmlTree(HtmlTag.UL);
    }

    /**
     * {@inheritDoc}
     */
    public void addLinkToPackageContent(PackageDoc pkg, String parsedPackageName,
            Set<String> printedPackageHeaders, Content contentListTree) {
        String packageName = pkg.name();
        //add link to summary
        Content link;
        if (packageName.length() == 0) {
            link = getHyperLink(getDocLink(
                    SectionName.UNNAMED_PACKAGE_ANCHOR),
                    defaultPackageLabel, "", "");
        } else {
            Content packageNameContent = getPackageLabel(parsedPackageName);
            packageNameContent.addContent(".*");
            link = getHyperLink(DocLink.fragment(parsedPackageName),
                    packageNameContent, "", "");
            printedPackageHeaders.add(parsedPackageName);
        }
        contentListTree.addContent(HtmlTree.LI(link));
    }

    /**
     * {@inheritDoc}
     */
    public void addContentsList(Content contentTree, Content contentListTree) {
        Content titleContent = getResource(
                "doclet.Constants_Summary");
        Content pHeading = HtmlTree.HEADING(HtmlConstants.TITLE_HEADING, true,
                HtmlStyle.title, titleContent);
        Content div = HtmlTree.DIV(HtmlStyle.header, pHeading);
        Content headingContent = getResource(
                "doclet.Contents");
        Content heading = HtmlTree.HEADING(HtmlConstants.CONTENT_HEADING, true,
                headingContent);
        if (configuration.allowTag(HtmlTag.SECTION)) {
            HtmlTree section = HtmlTree.SECTION(heading);
            section.addContent(contentListTree);
            div.addContent(section);
            mainTree.addContent(div);
        } else {
            div.addContent(heading);
            div.addContent(contentListTree);
            contentTree.addContent(div);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Content getConstantSummaries() {
        HtmlTree summariesDiv = new HtmlTree(HtmlTag.DIV);
        summariesDiv.addStyle(HtmlStyle.constantValuesContainer);
        return summariesDiv;
    }

    /**
     * {@inheritDoc}
     */
    public void addPackageName(String parsedPackageName, Content summariesTree, boolean first) {
        Content pkgNameContent;
        if (!first && configuration.allowTag(HtmlTag.SECTION)) {
            summariesTree.addContent(summaryTree);
        }
        if (parsedPackageName.length() == 0) {
            summariesTree.addContent(getMarkerAnchor(
                    SectionName.UNNAMED_PACKAGE_ANCHOR));
            pkgNameContent = defaultPackageLabel;
        } else {
            summariesTree.addContent(getMarkerAnchor(
                    parsedPackageName));
            pkgNameContent = getPackageLabel(parsedPackageName);
        }
        Content headingContent = new StringContent(".*");
        Content heading = HtmlTree.HEADING(HtmlConstants.PACKAGE_HEADING, true,
                pkgNameContent);
        heading.addContent(headingContent);
        if (configuration.allowTag(HtmlTag.SECTION)) {
            summaryTree = HtmlTree.SECTION(heading);
        } else {
            summariesTree.addContent(heading);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Content getClassConstantHeader() {
        HtmlTree ul = new HtmlTree(HtmlTag.UL);
        ul.addStyle(HtmlStyle.blockList);
        return ul;
    }

    /**
     * {@inheritDoc}
     */
    public void addClassConstant(Content summariesTree, Content classConstantTree) {
        if (configuration.allowTag(HtmlTag.SECTION)) {
            summaryTree.addContent(classConstantTree);
        } else {
            summariesTree.addContent(classConstantTree);
        }
    }

    /**
     * Get the table caption and header for the constant summary table
     *
     * @param cd classdoc to be documented
     * @return constant members header content
     */
    public Content getConstantMembersHeader(ClassDoc cd) {
        //generate links backward only to public classes.
        Content classlink = (cd.isPublic() || cd.isProtected()) ?
            getLink(new LinkInfoImpl(configuration,
                    LinkInfoImpl.Kind.CONSTANT_SUMMARY, cd)) :
            new StringContent(cd.qualifiedName());
        String name = cd.containingPackage().name();
        if (name.length() > 0) {
            Content cb = new ContentBuilder();
            cb.addContent(name);
            cb.addContent(".");
            cb.addContent(classlink);
            return getClassName(cb);
        } else {
            return getClassName(classlink);
        }
    }

    /**
     * Get the class name in the table caption and the table header.
     *
     * @param classStr the class name to print.
     * @return the table caption and header
     */
    protected Content getClassName(Content classStr) {
        Content caption = getTableCaption(classStr);
        Content table = (configuration.isOutputHtml5())
                ? HtmlTree.TABLE(HtmlStyle.constantsSummary, caption)
                : HtmlTree.TABLE(HtmlStyle.constantsSummary, constantsTableSummary, caption);
        table.addContent(getSummaryTableHeader(constantsTableHeader, "col"));
        return table;
    }

    /**
     * {@inheritDoc}
     */
    public void addConstantMembers(ClassDoc cd, List<FieldDoc> fields,
            Content classConstantTree) {
        currentClassDoc = cd;
        Content tbody = new HtmlTree(HtmlTag.TBODY);
        for (int i = 0; i < fields.size(); ++i) {
            HtmlTree tr = new HtmlTree(HtmlTag.TR);
            if (i%2 == 0)
                tr.addStyle(HtmlStyle.altColor);
            else
                tr.addStyle(HtmlStyle.rowColor);
            addConstantMember(fields.get(i), tr);
            tbody.addContent(tr);
        }
        Content table = getConstantMembersHeader(cd);
        table.addContent(tbody);
        Content li = HtmlTree.LI(HtmlStyle.blockList, table);
        classConstantTree.addContent(li);
    }

    /**
     * Add the row for the constant summary table.
     *
     * @param member the field to be documented.
     * @param trTree an htmltree object for the table row
     */
    private void addConstantMember(FieldDoc member, HtmlTree trTree) {
        trTree.addContent(getTypeColumn(member));
        trTree.addContent(getNameColumn(member));
        trTree.addContent(getValue(member));
    }

    /**
     * Get the type column for the constant summary table row.
     *
     * @param member the field to be documented.
     * @return the type column of the constant table row
     */
    private Content getTypeColumn(FieldDoc member) {
        Content anchor = getMarkerAnchor(currentClassDoc.qualifiedName() +
                "." + member.name());
        Content tdType = HtmlTree.TD(HtmlStyle.colFirst, anchor);
        Content code = new HtmlTree(HtmlTag.CODE);
        StringTokenizer mods = new StringTokenizer(member.modifiers());
        while(mods.hasMoreTokens()) {
            Content modifier = new StringContent(mods.nextToken());
            code.addContent(modifier);
            code.addContent(getSpace());
        }
        Content type = getLink(new LinkInfoImpl(configuration,
                LinkInfoImpl.Kind.CONSTANT_SUMMARY, member.type()));
        code.addContent(type);
        tdType.addContent(code);
        return tdType;
    }

    /**
     * Get the name column for the constant summary table row.
     *
     * @param member the field to be documented.
     * @return the name column of the constant table row
     */
    private Content getNameColumn(FieldDoc member) {
        Content nameContent = getDocLink(
                LinkInfoImpl.Kind.CONSTANT_SUMMARY, member, member.name(), false);
        Content code = HtmlTree.CODE(nameContent);
        return HtmlTree.TD(code);
    }

    /**
     * Get the value column for the constant summary table row.
     *
     * @param member the field to be documented.
     * @return the value column of the constant table row
     */
    private Content getValue(FieldDoc member) {
        Content valueContent = new StringContent(member.constantValueExpression());
        Content code = HtmlTree.CODE(valueContent);
        return HtmlTree.TD(HtmlStyle.colLast, code);
    }

    /**
     * {@inheritDoc}
     */
    public void addConstantSummaries(Content contentTree, Content summariesTree) {
        if (configuration.allowTag(HtmlTag.SECTION) && summaryTree != null) {
            summariesTree.addContent(summaryTree);
        }
        if (configuration.allowTag(HtmlTag.MAIN)) {
            mainTree.addContent(summariesTree);
            contentTree.addContent(mainTree);
        } else {
            contentTree.addContent(summariesTree);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void addFooter(Content contentTree) {
        Content htmlTree = (configuration.allowTag(HtmlTag.FOOTER))
                ? HtmlTree.FOOTER()
                : contentTree;
        addNavLinks(false, htmlTree);
        addBottom(htmlTree);
        if (configuration.allowTag(HtmlTag.FOOTER)) {
            contentTree.addContent(htmlTree);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void printDocument(Content contentTree) throws IOException {
        printHtmlDocument(null, true, contentTree);
    }
}
