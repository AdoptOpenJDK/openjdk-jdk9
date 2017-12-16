/*
 * Copyright (c) 1997, 2016, Oracle and/or its affiliates. All rights reserved.
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

package jdk.javadoc.internal.doclets.formats.html;

import java.util.Arrays;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

import jdk.javadoc.internal.doclets.formats.html.markup.HtmlConstants;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlStyle;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTag;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTree;
import jdk.javadoc.internal.doclets.formats.html.markup.StringContent;
import jdk.javadoc.internal.doclets.toolkit.Content;
import jdk.javadoc.internal.doclets.toolkit.FieldWriter;
import jdk.javadoc.internal.doclets.toolkit.MemberSummaryWriter;

/**
 * Writes field documentation in HTML format.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Robert Field
 * @author Atul M Dambalkar
 * @author Jamie Ho (rewrite)
 * @author Bhavesh Patel (Modified)
 */
public class FieldWriterImpl extends AbstractMemberWriter
    implements FieldWriter, MemberSummaryWriter {

    public FieldWriterImpl(SubWriterHolderWriter writer, TypeElement typeElement) {
        super(writer, typeElement);
    }

    public FieldWriterImpl(SubWriterHolderWriter writer) {
        super(writer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getMemberSummaryHeader(TypeElement typeElement,
            Content memberSummaryTree) {
        memberSummaryTree.addContent(HtmlConstants.START_OF_FIELD_SUMMARY);
        Content memberTree = writer.getMemberTreeHeader();
        writer.addSummaryHeader(this, typeElement, memberTree);
        return memberTree;
    }

    /**
     * {@inheritDoc}
     */
    public void addMemberTree(Content memberSummaryTree, Content memberTree) {
        writer.addMemberTree(memberSummaryTree, memberTree);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getFieldDetailsTreeHeader(TypeElement typeElement, Content memberDetailsTree) {
        memberDetailsTree.addContent(HtmlConstants.START_OF_FIELD_DETAILS);
        Content fieldDetailsTree = writer.getMemberTreeHeader();
        fieldDetailsTree.addContent(writer.getMarkerAnchor(
                SectionName.FIELD_DETAIL));
        Content heading = HtmlTree.HEADING(HtmlConstants.DETAILS_HEADING,
                contents.fieldDetailsLabel);
        fieldDetailsTree.addContent(heading);
        return fieldDetailsTree;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getFieldDocTreeHeader(VariableElement field, Content fieldDetailsTree) {
        fieldDetailsTree.addContent(writer.getMarkerAnchor(name(field)));
        Content fieldTree = writer.getMemberTreeHeader();
        Content heading = new HtmlTree(HtmlConstants.MEMBER_HEADING);
        heading.addContent(name(field));
        fieldTree.addContent(heading);
        return fieldTree;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getSignature(VariableElement field) {
        Content pre = new HtmlTree(HtmlTag.PRE);
        writer.addAnnotationInfo(field, pre);
        addModifiers(field, pre);
        Content fieldlink = writer.getLink(new LinkInfoImpl(
                configuration, LinkInfoImpl.Kind.MEMBER, field.asType()));
        pre.addContent(fieldlink);
        pre.addContent(" ");
        if (configuration.linksource) {
            Content fieldName = new StringContent(name(field));
            writer.addSrcLink(field, fieldName, pre);
        } else {
            addName(name(field), pre);
        }
        return pre;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDeprecated(VariableElement field, Content fieldTree) {
        addDeprecatedInfo(field, fieldTree);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addComments(VariableElement field, Content fieldTree) {
        if (!utils.getFullBody(field).isEmpty()) {
            writer.addInlineComment(field, fieldTree);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTags(VariableElement field, Content fieldTree) {
        writer.addTagsInfo(field, fieldTree);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getFieldDetails(Content fieldDetailsTree) {
        if (configuration.allowTag(HtmlTag.SECTION)) {
            HtmlTree htmlTree = HtmlTree.SECTION(getMemberTree(fieldDetailsTree));
            return htmlTree;
        }
        return getMemberTree(fieldDetailsTree);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getFieldDoc(Content fieldTree,
            boolean isLastContent) {
        return getMemberTree(fieldTree, isLastContent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addSummaryLabel(Content memberTree) {
        Content label = HtmlTree.HEADING(HtmlConstants.SUMMARY_HEADING,
                contents.fieldSummaryLabel);
        memberTree.addContent(label);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTableSummary() {
        return resources.getText("doclet.Member_Table_Summary",
                resources.getText("doclet.Field_Summary"),
                resources.getText("doclet.fields"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getCaption() {
        return contents.fields;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getSummaryTableHeader(Element member) {
        List<String> header = Arrays.asList(writer.getModifierTypeHeader(),
                resources.getText("doclet.Field"), resources.getText("doclet.Description"));
        return header;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addSummaryAnchor(TypeElement typeElement, Content memberTree) {
        memberTree.addContent(writer.getMarkerAnchor(
                SectionName.FIELD_SUMMARY));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addInheritedSummaryAnchor(TypeElement typeElement, Content inheritedTree) {
        inheritedTree.addContent(writer.getMarkerAnchor(
                SectionName.FIELDS_INHERITANCE, configuration.getClassName(typeElement)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addInheritedSummaryLabel(TypeElement typeElement, Content inheritedTree) {
        Content classLink = writer.getPreQualifiedClassLink(
                LinkInfoImpl.Kind.MEMBER, typeElement, false);
        Content label = new StringContent(utils.isClass(typeElement)
                ? configuration.getText("doclet.Fields_Inherited_From_Class")
                : configuration.getText("doclet.Fields_Inherited_From_Interface"));
        Content labelHeading = HtmlTree.HEADING(HtmlConstants.INHERITED_SUMMARY_HEADING,
                label);
        labelHeading.addContent(Contents.SPACE);
        labelHeading.addContent(classLink);
        inheritedTree.addContent(labelHeading);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addSummaryLink(LinkInfoImpl.Kind context, TypeElement typeElement, Element member,
            Content tdSummary) {
        Content memberLink = HtmlTree.SPAN(HtmlStyle.memberNameLink,
                writer.getDocLink(context, typeElement , member, name(member), false));
        Content code = HtmlTree.CODE(memberLink);
        tdSummary.addContent(code);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addInheritedSummaryLink(TypeElement typeElement, Element member, Content linksTree) {
        linksTree.addContent(
                writer.getDocLink(LinkInfoImpl.Kind.MEMBER, typeElement, member,
                name(member), false));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addSummaryType(Element member, Content tdSummaryType) {
        addModifierAndType(member, member.asType(), tdSummaryType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Content getDeprecatedLink(Element member) {
        String name = utils.getFullyQualifiedName(member) + "." + member.getSimpleName();
        return writer.getDocLink(LinkInfoImpl.Kind.MEMBER, member, name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Content getNavSummaryLink(TypeElement typeElement, boolean link) {
        if (link) {
            if (typeElement == null) {
                return writer.getHyperLink(
                        SectionName.FIELD_SUMMARY,
                        contents.navField);
            } else {
                return writer.getHyperLink(
                        SectionName.FIELDS_INHERITANCE,
                        configuration.getClassName(typeElement), contents.navField);
            }
        } else {
            return contents.navField;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addNavDetailLink(boolean link, Content liNav) {
        if (link) {
            liNav.addContent(writer.getHyperLink(
                    SectionName.FIELD_DETAIL,
                    contents.navField));
        } else {
            liNav.addContent(contents.navField);
        }
    }
}
