/*
 * Copyright (c) 2003, 2016, Oracle and/or its affiliates. All rights reserved.
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

import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

import jdk.javadoc.internal.doclets.formats.html.markup.HtmlConstants;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTree;
import jdk.javadoc.internal.doclets.formats.html.markup.StringContent;
import jdk.javadoc.internal.doclets.toolkit.AnnotationTypeOptionalMemberWriter;
import jdk.javadoc.internal.doclets.toolkit.Content;
import jdk.javadoc.internal.doclets.toolkit.MemberSummaryWriter;


/**
 * Writes annotation type optional member documentation in HTML format.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Jamie Ho
 * @author Bhavesh Patel (Modified)
 */
public class AnnotationTypeOptionalMemberWriterImpl extends
        AnnotationTypeRequiredMemberWriterImpl
    implements AnnotationTypeOptionalMemberWriter, MemberSummaryWriter {

    /**
     * Construct a new AnnotationTypeOptionalMemberWriterImpl.
     *
     * @param writer         the writer that will write the output.
     * @param annotationType the AnnotationType that holds this member.
     */
    public AnnotationTypeOptionalMemberWriterImpl(SubWriterHolderWriter writer,
        TypeElement annotationType) {
        super(writer, annotationType);
    }

    /**
     * {@inheritDoc}
     */
    public Content getMemberSummaryHeader(TypeElement typeElement,
            Content memberSummaryTree) {
        memberSummaryTree.addContent(
                HtmlConstants.START_OF_ANNOTATION_TYPE_OPTIONAL_MEMBER_SUMMARY);
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
    public void addDefaultValueInfo(Element member, Content annotationDocTree) {
        if (utils.isAnnotationType(member)) {
            ExecutableElement ee = (ExecutableElement)member;
            AnnotationValue value = ee.getDefaultValue();
            if (value != null) {
                Content dt = HtmlTree.DT(contents.default_);
                Content dl = HtmlTree.DL(dt);
                Content dd = HtmlTree.DD(new StringContent(value.toString()));
                dl.addContent(dd);
                annotationDocTree.addContent(dl);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void addSummaryLabel(Content memberTree) {
        Content label = HtmlTree.HEADING(HtmlConstants.SUMMARY_HEADING,
                contents.annotateTypeOptionalMemberSummaryLabel);
        memberTree.addContent(label);
    }

    /**
     * {@inheritDoc}
     */
    public String getTableSummary() {
        return resources.getText("doclet.Member_Table_Summary",
                resources.getText("doclet.Annotation_Type_Optional_Member_Summary"),
                resources.getText("doclet.annotation_type_optional_members"));
    }

    /**
     * {@inheritDoc}
     */
    public Content getCaption() {
        return configuration.getContent("doclet.Annotation_Type_Optional_Members");
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getSummaryTableHeader(Element member) {
        List<String> header = Arrays.asList(writer.getModifierTypeHeader(),
                resources.getText("doclet.Annotation_Type_Optional_Member"),
                resources.getText("doclet.Description"));
        return header;
    }

    /**
     * {@inheritDoc}
     */
    public void addSummaryAnchor(TypeElement typeElement, Content memberTree) {
        memberTree.addContent(writer.getMarkerAnchor(
                SectionName.ANNOTATION_TYPE_OPTIONAL_ELEMENT_SUMMARY));
    }

    /**
     * {@inheritDoc}
     */
    protected Content getNavSummaryLink(TypeElement typeElement, boolean link) {
        if (link) {
            return writer.getHyperLink(
                    SectionName.ANNOTATION_TYPE_OPTIONAL_ELEMENT_SUMMARY,
                    contents.navAnnotationTypeOptionalMember);
        } else {
            return contents.navAnnotationTypeOptionalMember;
        }
    }
}
