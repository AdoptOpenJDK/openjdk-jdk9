/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.xml.internal.messaging.saaj.util.stax;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Node;

import com.sun.xml.internal.messaging.saaj.soap.impl.BodyImpl;
import com.sun.xml.internal.messaging.saaj.soap.impl.EnvelopeImpl;

/**
 * "Hybrid" reader which
 * @author desagar
 *
 */
public class LazyEnvelopeStaxReader extends com.sun.xml.internal.org.jvnet.staxex.util.DOMStreamReader {
//    EnvelopeImpl env;
    XMLStreamReader payloadReader = null;
    boolean usePayloadReaderDelegate = false;
    private QName bodyQName;

    public LazyEnvelopeStaxReader(EnvelopeImpl env) throws SOAPException, XMLStreamException {
        super(env);
//        this.env = env;
        bodyQName = new QName(env.getNamespaceURI(), "Body");
        payloadReader = env.getStaxBridge().getPayloadReader();
        int eventType = getEventType();
        while (eventType != START_ELEMENT) {
            eventType = nextTag();
        }
    }

    public Object getProperty(String name) throws IllegalArgumentException {
        if (usePayloadReaderDelegate) return payloadReader.getProperty(name);
        return super.getProperty(name);
    }

    public int next() throws XMLStreamException {
//        boolean previouslyUsingPayloadReader = usePayloadReaderDelegate;
        //call checkReaderStatus to advance to payloadReader if needed
        checkReaderStatus(true);

        if (usePayloadReaderDelegate) return payloadReader.getEventType();

        //if we just moved to payload reader, don't advance the pointer
//        if (usePayloadReaderDelegate && !previouslyUsingPayloadReader) return payloadReader.getEventType();

//        if (usePayloadReaderDelegate) return payloadReader.next();
        return getEventType();
    }

    public void require(int type, String namespaceURI, String localName)
            throws XMLStreamException {
        if (usePayloadReaderDelegate) payloadReader.require(type, namespaceURI, localName);
        else super.require(type, namespaceURI, localName);
    }

    public String getElementText() throws XMLStreamException {
        if (usePayloadReaderDelegate) return payloadReader.getElementText();
        return super.getElementText();
    }

    public int nextTag() throws XMLStreamException {
        if (usePayloadReaderDelegate) return payloadReader.nextTag();
        return super.nextTag();
    }

    public boolean hasNext() throws XMLStreamException {
        checkReaderStatus(false);
        boolean hasNext;
        if (usePayloadReaderDelegate) {
            hasNext = payloadReader.hasNext();
        } else {
            hasNext = super.hasNext();
        }

        /*if (!hasNext && payloadReader != null) {
            usePayloadReaderDelegate = true;
            hasNext = payloadReader.hasNext();
        }*/
        return hasNext;
    }

    private void checkReaderStatus(boolean advanceToNext) throws XMLStreamException {
        //if we are using payloadReader, make sure it is not exhausted
        //if it is, return to DOM based reader for remaining end elements (body and envelope)
        if (usePayloadReaderDelegate) {
            if (!payloadReader.hasNext()) {
                usePayloadReaderDelegate = false;
            }
        } else if (START_ELEMENT == getEventType()) {
            //if not on payload reader, check if we need to switch to payload reader

            //if the current event is the SOAP body element start,
            //and the body is lazy, switch to the payload reader
            if (bodyQName.equals(getName())) {
                //if we are just switching to payload reader, don't advance...payload reader
                //will already be on the first payload element
                usePayloadReaderDelegate = true;
                advanceToNext = false;
            }
        }

        if (advanceToNext) {
            if (usePayloadReaderDelegate) {
                payloadReader.next();
            } else {
                super.next();
            }
        }
    }

    public void close() throws XMLStreamException {
        if (usePayloadReaderDelegate) payloadReader.close();
        else super.close();
    }

    public String getNamespaceURI(String prefix) {
        if (usePayloadReaderDelegate) return payloadReader.getNamespaceURI(prefix);
        return super.getNamespaceURI(prefix);
    }

    public boolean isStartElement() {
        if (usePayloadReaderDelegate) return payloadReader.isStartElement();
        return super.isStartElement();
    }

    public boolean isEndElement() {
        if (usePayloadReaderDelegate) return payloadReader.isEndElement();
        return super.isEndElement();
    }

    public boolean isCharacters() {
        if (usePayloadReaderDelegate) return payloadReader.isCharacters();
        return super.isEndElement();
    }

    public boolean isWhiteSpace() {
        if (usePayloadReaderDelegate) return payloadReader.isWhiteSpace();
        return super.isWhiteSpace();
    }

    public String getAttributeValue(String namespaceURI, String localName) {
        if (usePayloadReaderDelegate) return payloadReader.getAttributeValue(namespaceURI, localName);
        return super.getAttributeValue(namespaceURI, localName);
    }

    public int getAttributeCount() {
        if (usePayloadReaderDelegate) return payloadReader.getAttributeCount();
        return super.getAttributeCount();
    }

    public QName getAttributeName(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getAttributeName(index);
        return super.getAttributeName(index);
    }

    public String getAttributeNamespace(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getAttributeNamespace(index);
        return super.getAttributeNamespace(index);
    }

    public String getAttributeLocalName(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getAttributeLocalName(index);
        return super.getAttributeLocalName(index);
    }

    public String getAttributePrefix(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getAttributePrefix(index);
        return super.getAttributePrefix(index);
    }

    public String getAttributeType(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getAttributeType(index);
        return super.getAttributeType(index);
    }

    public String getAttributeValue(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getAttributeValue(index);
        return super.getAttributeValue(index);
    }

    public boolean isAttributeSpecified(int index) {
        if (usePayloadReaderDelegate) return payloadReader.isAttributeSpecified(index);
        return super.isAttributeSpecified(index);
    }

    public int getNamespaceCount() {
        if (usePayloadReaderDelegate) return payloadReader.getNamespaceCount();
        return super.getNamespaceCount();
    }

    public String getNamespacePrefix(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getNamespacePrefix(index);
        return super.getNamespacePrefix(index);
    }

    public String getNamespaceURI(int index) {
        if (usePayloadReaderDelegate) return payloadReader.getNamespaceURI(index);
        return super.getNamespaceURI(index);
    }

    public NamespaceContext getNamespaceContext() {
        if (usePayloadReaderDelegate) return payloadReader.getNamespaceContext();
        return super.getNamespaceContext();
    }

    public int getEventType() {
        if (usePayloadReaderDelegate) return payloadReader.getEventType();
        return super.getEventType();
    }

    public String getText() {
        if (usePayloadReaderDelegate) return payloadReader.getText();
        return super.getText();
    }

    public char[] getTextCharacters() {
        if (usePayloadReaderDelegate) return payloadReader.getTextCharacters();
        return super.getTextCharacters();
    }

    public int getTextCharacters(int sourceStart, char[] target,
            int targetStart, int length) throws XMLStreamException {
        if (usePayloadReaderDelegate) return payloadReader.getTextCharacters(sourceStart, target, targetStart,
                length);
        return super.getTextCharacters(sourceStart, target, targetStart, length);
    }

    public int getTextStart() {
        if (usePayloadReaderDelegate) return payloadReader.getTextStart();
        return super.getTextStart();
    }

    public int getTextLength() {
        if (usePayloadReaderDelegate) return payloadReader.getTextLength();
        return super.getTextLength();
    }

    public String getEncoding() {
        if (usePayloadReaderDelegate) return payloadReader.getEncoding();
        return super.getEncoding();
    }

    public boolean hasText() {
        if (usePayloadReaderDelegate) return payloadReader.hasText();
        return super.hasText();
    }

    public Location getLocation() {
        if (usePayloadReaderDelegate) return payloadReader.getLocation();
        return super.getLocation();
    }

    public QName getName() {
        if (usePayloadReaderDelegate) return payloadReader.getName();
        return super.getName();
    }

    public String getLocalName() {
        if (usePayloadReaderDelegate) return payloadReader.getLocalName();
        return super.getLocalName();
    }

    public boolean hasName() {
        if (usePayloadReaderDelegate) return payloadReader.hasName();
        return super.hasName();
    }

    public String getNamespaceURI() {
        if (usePayloadReaderDelegate) return payloadReader.getNamespaceURI();
        return super.getNamespaceURI();
    }

    public String getPrefix() {
        if (usePayloadReaderDelegate) return payloadReader.getPrefix();
        return super.getPrefix();
    }

    public String getVersion() {
        if (usePayloadReaderDelegate) return payloadReader.getVersion();
        return super.getVersion();
    }

    public boolean isStandalone() {
        if (usePayloadReaderDelegate) return payloadReader.isStandalone();
        return super.isStandalone();
    }

    public boolean standaloneSet() {
        if (usePayloadReaderDelegate) return payloadReader.standaloneSet();
        return super.standaloneSet();
    }

    public String getCharacterEncodingScheme() {
        if (usePayloadReaderDelegate) return payloadReader.getCharacterEncodingScheme();
        return super.getCharacterEncodingScheme();
    }

    public String getPITarget() {
        if (usePayloadReaderDelegate) return payloadReader.getPITarget();
        return super.getPITarget();
    }

    public String getPIData() {
        if (usePayloadReaderDelegate) return payloadReader.getPIData();
        return super.getPIData();
    }

    //make sure that message is not realized as a result of call
    //to getFirstChild
    protected Node getFirstChild(Node node) {
        if (node instanceof BodyImpl) {
            return ((BodyImpl) node).getFirstChildNoMaterialize();
        } else {
            return node.getFirstChild();
        }
    }

    protected Node getNextSibling(Node node) {
        if (node instanceof BodyImpl) {
            //body is not expected to have a next sibling - even if it does
            //we would have to materialize the node to retrieve it.
            //Since we don't want to materialize it right now, just return null
            return null;
        }
        return node.getNextSibling();
    }

}
