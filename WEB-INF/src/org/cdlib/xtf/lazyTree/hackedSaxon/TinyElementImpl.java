package org.cdlib.xtf.lazyTree.hackedSaxon;
import net.sf.saxon.event.Receiver;
import net.sf.saxon.event.ReceiverOptions;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.style.StandardNames;
import net.sf.saxon.tree.DOMExceptionImpl;
import net.sf.saxon.type.Type;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;

import javax.xml.transform.TransformerException;
import java.nio.CharBuffer;

/**
  * A node in the XML parse tree representing an XML element.<P>
  * This class is an implementation of NodeInfo and also implements the
  * DOM Element interface
  * @author Michael H. Kay
  */

final class TinyElementImpl extends TinyParentNodeImpl
    implements Element {

    /**
    * Constructor
    */

    public TinyElementImpl(TinyDocumentImpl doc, int nodeNr) {
        this.document = doc;
        this.nodeNr = nodeNr;
    }

    /**
    * Return the type of node.
    * @return Type.ELEMENT
    */

    public final int getNodeKind() {
        return Type.ELEMENT;
    }

    /**
    * Get the base URI of this element node. This will be the same as the System ID unless
    * xml:base has been used.
    */

    public String getBaseURI() {
        String xmlBase = getAttributeValue(StandardNames.XML_BASE);
        if (xmlBase!=null) {
            return xmlBase;
        }
        String startSystemId = getSystemId();
        NodeInfo parent = getParent();
        if (parent==null) {
            return startSystemId;
        }
        String parentSystemId = parent.getSystemId();
        if (startSystemId.equals(parentSystemId)) {
            return parent.getBaseURI();
        } else {
            return startSystemId;
        }
    }

    /**
    * Get the type annotation of this node, if any
    * Returns Type.UNTYPED_ANY if there is no type annotation
    */

    public int getTypeAnnotation() {
        return document.getElementAnnotation(nodeNr);
    }

    /**
    * Output all namespace nodes associated with this element.
    * @param out The relevant outputter
    * @param includeAncestors True if namespaces associated with ancestor
    * elements must also be output; false if these are already known to be
    * on the result tree.
    */

    public void outputNamespaceNodes(Receiver out, boolean includeAncestors)
                throws TransformerException {

        int ns = document.beta[nodeNr]; // by convention
        if (ns>0 ) {
            while (ns < document.numberOfNamespaces &&
                    document.namespaceParent[ns] == nodeNr ) {
                int nscode = document.namespaceCode[ns];
                out.namespace(nscode, 0);
                ns++;
            }
        }

        // now add the namespaces defined on the ancestor nodes. We rely on the receiver
        // to eliminate multiple declarations of the same prefix

        if (includeAncestors && document.isUsingNamespaces()) {
            NodeInfo parent = getParent();
            if (parent != null) {
                parent.outputNamespaceNodes(out, true);
            }
            // terminates when the parent is a root node
        }
    }

    /**
     * Returns whether this node (if it is an element) has any attributes.
     * @return <code>true</code> if this node has any attributes,
     *   <code>false</code> otherwise.
     * @since DOM Level 2
     */

    public boolean hasAttributes() {
        return document.alpha[nodeNr] >= 0;
    }

    /**
     * Find the value of a given attribute of this node. <BR>
     * This method is defined on all nodes to meet XSL requirements, but for nodes
     * other than elements it will always return null.
     * @param uri the namespace uri of an attribute
     * @param localName the local name of an attribute
     * @return the value of the attribute, if it exists, otherwise null
     */

//    public String getAttributeValue( String uri, String localName ) {
//        int f = document.getNamePool().getFingerprint(uri, localName);
//        return getAttributeValue(f);
//    }

    /**
    * Get the value of a given attribute of this node
    * @param fingerprint The fingerprint of the attribute name
    * @return the attribute value if it exists or null if not
    */

    public String getAttributeValue(int fingerprint) {
        int a = document.alpha[nodeNr];
        if (a<0) return null;
        while (a < document.numberOfAttributes && document.attParent[a] == nodeNr) {
            if ((document.attCode[a] & 0xfffff) == fingerprint ) {
                return document.attValue[a].toString();
            }
            a++;
        }
        return null;
    }

    /**
    * Set the value of an attribute on the current element. This affects subsequent calls
    * of getAttribute() for that element.
    * @param name The name of the attribute to be set. Any prefix is interpreted relative
    * to the namespaces defined for this element.
    * @param value The new value of the attribute. Set this to null to remove the attribute.
    */

    public void setAttribute(String name, String value ) throws DOMException {
        throw new DOMExceptionImpl((short)9999, "Saxon DOM is not updateable");
    }

    /**
    * Copy this node to a given outputter
    * @param whichNamespaces indicates which namespaces should be copied: all, none,
    * or local (i.e., those not declared on a parent element)
    */

    public void copy(Receiver receiver, int whichNamespaces, boolean copyAnnotations) throws TransformerException {

        // Based on an algorithm supplied by Ruud Diterwich

        // Performance measurements show that this achieves no speed-up over the OLD version
        // (in 7.4). So might as well switch back.

        // control vars
        short level = -1;
        boolean closePending = false;
        short startLevel = document.depth[nodeNr];
        boolean first = true;
        int next = nodeNr;

        // document.diagnosticDump();

        do {

            // determine node depth
            short nodeLevel = document.depth[next];

            // extra close required?
            if (closePending) {
                level++;
            }

            // close former elements
            for (; level > nodeLevel; level--) {
                receiver.endElement();
            }

            // new node level
            level = nodeLevel;

            // output depends on node type
            switch (document.nodeKind[next]) {
                case Type.ELEMENT :

                    // start element
                    receiver.startElement(document.nameCode[next],
                                            (copyAnnotations ? document.getElementAnnotation(next): -1),
                                            (first ? ReceiverOptions.DISINHERIT_NAMESPACES : 0));

                    // there is an element to close
                    closePending = true;

                    // output namespaces
                    if (whichNamespaces != NO_NAMESPACES) {
                        if (first) {
                            outputNamespaceNodes(receiver, whichNamespaces==ALL_NAMESPACES);
                        } else {
                            int ns = document.beta[next]; // by convention
                            if (ns>0 ) {
                                while (ns < document.numberOfNamespaces &&
                                        document.namespaceParent[ns] == next ) {
                                    int nscode = document.namespaceCode[ns];
                                    receiver.namespace(nscode, 0);
                                    ns++;
                                }
                            }
                        }
                    }
                    first = false;

                    // output attributes

                    int att = document.alpha[next];
                    if (att >= 0) {
                        while (att < document.numberOfAttributes && document.attParent[att] == next ) {
                            int attCode = document.attCode[att];
                            int attType = (copyAnnotations ? document.getAttributeAnnotation(att) : -1);
                            receiver.attribute(attCode, attType, document.attValue[att], 0);
                            att++;
                        }
                    }

                    // start content
                    receiver.startContent();
                    break;

                case Type.TEXT :

                    // don't close text nodes
                    closePending = false;

                    // output characters
                    int start = document.alpha[next];
                    int len = document.beta[next];
                    receiver.characters(CharBuffer.wrap(document.charBuffer, start, len), 0);
                        // document.charBuffer is a char[], so the third argument is the length
                    break;

                case Type.COMMENT :

                    // don't close text nodes
                    closePending = false;

                    // output copy of comment
                    start = document.alpha[next];
                    len = document.beta[next];
                    if (len>0) {
                        receiver.comment(CharBuffer.wrap(document.commentBuffer, start, start+len), 0);
                             // document.commentBuffer is a StringBuffer, so the third argument is the end position!
                    } else {
                        receiver.comment("", 0);
                    }
                    break;

                case Type.PROCESSING_INSTRUCTION :

                    // don't close text nodes
                    closePending = false;

                    // output copy of PI
                    NodeInfo pi = document.getNode(next);
                    receiver.processingInstruction(pi.getLocalPart(), pi.getStringValue(), 0);
                    break;
            }

            next++;

        } while (next < document.numberOfNodes && document.depth[next] > startLevel);

        // close all remaining elements
        if (closePending) {
            level++;
        }
        for (; level > startLevel; level--) {
            receiver.endElement();
        }
    }

// --Recycle Bin START (22/04/04 21:12):
//    public void copyRUUD(Receiver receiver, int whichNamespaces, boolean copyAnnotations) throws TransformerException {
//
//        // New code supplied by Ruud Diterwich
//
//        // control vars
//        short level = -1;
//        boolean closePending = false;
//        boolean first = true;
//        short startLevel = -1;
//
//        // traverse all nodes
//        AxisIterator descendants = iterateAxis(Axis.DESCENDANT_OR_SELF);
//        while (true) {
//
//            // get node
//            NodeInfo node = (NodeInfo) descendants.next();
//            if (node == null) {
//                break;
//            }
//
//            // determine node depth
//            short nodeLevel = document.depth[((TinyNodeImpl)node).nodeNr];
//            if (first) {
//                startLevel = nodeLevel;
//            }
//
//            // extra close required?
//            if (closePending) {
//                level++;
//            }
//
//            // close former elements
//            for (; level > nodeLevel; level--) {
//                receiver.endElement();
//            }
//
//            // new node level
//            level = nodeLevel;
//
//            // output depends on node type
//            switch (node.getNodeKind()) {
//                case Type.ELEMENT :
//
//                    // start element
//                    receiver.startElement(node.getNameCode(),
//                                            (copyAnnotations ? node.getTypeAnnotation(): -1),
//                                            0);
//
//                    // there is an element to close
//                    closePending = true;
//
//                    // output namespaces
//                    if (whichNamespaces != NO_NAMESPACES) {
//                        node.outputNamespaceNodes(receiver, first && whichNamespaces==ALL_NAMESPACES);
//                    }
//                    first = false;
//
//                    // output attributes
//                    AxisIterator atts = node.iterateAxis(Axis.ATTRIBUTE);
//                    while (true) {
//                        NodeInfo att = (NodeInfo) atts.next();
//                        if (att == null) {
//                            break;
//                        }
//                        receiver.attribute(att.getNameCode(),
//                                           (copyAnnotations ? att.getTypeAnnotation(): -1),
//                                           att.getStringValue(),
//                                           0);
//                    }
//
//                    // start content
//                    receiver.startContent();
//                    break;
//
//                case Type.TEXT :
//
//                    // don't close text nodes
//                    closePending = false;
//
//                    // output characters
//                    receiver.characters(node.getStringValue(), 0);
//                    break;
//
//                case Type.COMMENT :
//
//                    // don't close text nodes
//                    closePending = false;
//
//                    // output characters
//                    receiver.comment(node.getStringValue(), 0);
//                    break;
//
//                case Type.PROCESSING_INSTRUCTION :
//
//                    // don't close text nodes
//                    closePending = false;
//
//                    // output characters
//                    receiver.processingInstruction(node.getLocalPart(), node.getStringValue(), 0);
//                    break;
//            }
//        }
//
//        // close all remaining elements
//        if (closePending) {
//            level++;
//        }
//        for (; level > startLevel; level--) {
//            receiver.endElement();
//        }
//    }
// --Recycle Bin STOP (22/04/04 21:12)

/*
    public void copyOLD(Receiver out, int whichNamespaces, boolean copyAnnotations) throws TransformerException {

        int nc = getNameCode();
        int typeCode = (copyAnnotations ? getTypeAnnotation() : 0);
        out.startElement(nc, typeCode, 0);

        // output the namespaces

        if (whichNamespaces != NO_NAMESPACES) {
            outputNamespaceNodes(out, whichNamespaces==ALL_NAMESPACES);
        }

        // output the attributes

        int a = document.alpha[nodeNr];
        if (a >= 0) {
            while (a < document.numberOfAttributes && document.attParent[a] == nodeNr) {
                document.getAttributeNode(a).copy(out, NO_NAMESPACES, copyAnnotations);
                a++;
            }
        }

        // output the children

        AxisIterator children =
            getEnumeration(Axis.CHILD, AnyNodeTest.getInstance());

        int childNamespaces = (whichNamespaces==NO_NAMESPACES ? NO_NAMESPACES : LOCAL_NAMESPACES);
        while (children.hasNext()) {
            NodeInfo next = (NodeInfo)children.next();
            next.copy(out, childNamespaces, copyAnnotations);
        }
        out.endElement();
    }
  */
}

//
// The contents of this file are subject to the Mozilla Public License Version 1.0 (the "License");
// you may not use this file except in compliance with the License. You may obtain a copy of the
// License at http://www.mozilla.org/MPL/
//
// Software distributed under the License is distributed on an "AS IS" basis,
// WITHOUT WARRANTY OF ANY KIND, either express or implied.
// See the License for the specific language governing rights and limitations under the License.
//
// The Original Code is: all this file.
//
// The Initial Developer of the Original Code is Michael H. Kay.
//
// The new copy() routine (in version 7.4.1) is contributed by Ruud Diterwich
//
// Portions created by Martin Haye are Copyright (C) Regents of the University 
// of California. All Rights Reserved. 
//
// Contributor(s): Ruud Diterwich, Martin Haye
//
