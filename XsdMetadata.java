import org.apache.ws.commons.schema.*;
import org.apache.ws.commons.schema.utils.NamespaceMap;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;

// XsdMetadata class to hold parsed XSD information
class XsdMetadata {
    private String elementType;
    private String name;
    private String baseType; // Will contain XSD base types like xs:string, xs:decimal, etc.
    private Map<String, XsdMetadata> attributes;
    private Map<String, XsdMetadata> childElements;
    private String minOccurs;
    private String maxOccurs;
    
    public XsdMetadata() {
        this.attributes = new HashMap<>();
        this.childElements = new HashMap<>();
    }
    
    // Getters and setters
    public String getElementType() { return elementType; }
    public void setElementType(String elementType) { this.elementType = elementType; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getBaseType() { return baseType; }
    public void setBaseType(String baseType) { this.baseType = baseType; }
    
    public Map<String, XsdMetadata> getAttributes() { return attributes; }
    public void setAttributes(Map<String, XsdMetadata> attributes) { this.attributes = attributes; }
    
    public Map<String, XsdMetadata> getChildElements() { return childElements; }
    public void setChildElements(Map<String, XsdMetadata> childElements) { this.childElements = childElements; }
    
    public String getMinOccurs() { return minOccurs; }
    public void setMinOccurs(String minOccurs) { this.minOccurs = minOccurs; }
    
    public String getMaxOccurs() { return maxOccurs; }
    public void setMaxOccurs(String maxOccurs) { this.maxOccurs = maxOccurs; }
    
    public void addAttribute(String name, XsdMetadata attribute) {
        this.attributes.put(name, attribute);
    }
    
    public void addChildElement(String name, XsdMetadata child) {
        this.childElements.put(name, child);
    }
    
    @Override
    public String toString() {
        return "XsdMetadata{" +
                "elementType='" + elementType + '\'' +
                ", name='" + name + '\'' +
                ", baseType='" + baseType + '\'' +
                ", minOccurs='" + minOccurs + '\'' +
                ", maxOccurs='" + maxOccurs + '\'' +
                ", attributes=" + attributes.keySet() +
                ", childElements=" + childElements.keySet() +
                '}';
    }
}

// Main XSD Parser class using Apache XmlSchema
public class XsdParser {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private XmlSchemaCollection schemaCollection;
    private Map<QName, XsdMetadata> processedTypes;
    
    public XsdParser() {
        this.schemaCollection = new XmlSchemaCollection();
        this.processedTypes = new HashMap<>();
    }
    
    public XsdMetadata parseXsd(String xsdFilePath) throws Exception {
        File xsdFile = new File(xsdFilePath);
        
        // Create schema collection and set base URI for resolving includes/imports
        schemaCollection.setBaseUri(xsdFile.getParentFile().toURI().toString());
        
        // Read the schema - this will automatically handle includes and imports
        FileInputStream is = new FileInputStream(xsdFile);
        XmlSchema schema = schemaCollection.read(new StreamSource(is), null);
        is.close();
        
        // Create root metadata
        XsdMetadata rootMetadata = new XsdMetadata();
        rootMetadata.setElementType("root");
        rootMetadata.setName("XSD_ROOT");
        
        // Process all global elements
        for (XmlSchemaElement element : schema.getElements().values()) {
            XsdMetadata elementMetadata = processElement(element);
            if (elementMetadata != null && elementMetadata.getName() != null) {
                rootMetadata.addChildElement(elementMetadata.getName(), elementMetadata);
            }
        }
        
        return rootMetadata;
    }
    
    private XsdMetadata processElement(XmlSchemaElement element) {
        XsdMetadata metadata = new XsdMetadata();
        metadata.setElementType("element");
        metadata.setName(element.getName());
        
        // Set occurrence constraints
        metadata.setMinOccurs(String.valueOf(element.getMinOccurs()));
        metadata.setMaxOccurs(element.getMaxOccurs() == Long.MAX_VALUE ? "unbounded" : 
                              String.valueOf(element.getMaxOccurs()));
        
        // Get the element's type
        XmlSchemaType schemaType = element.getSchemaType();
        
        if (schemaType != null) {
            if (schemaType instanceof XmlSchemaSimpleType) {
                processSimpleType((XmlSchemaSimpleType) schemaType, metadata);
            } else if (schemaType instanceof XmlSchemaComplexType) {
                processComplexType((XmlSchemaComplexType) schemaType, metadata);
            }
        } else if (element.getSchemaTypeName() != null) {
            // Element references a type by name
            QName typeName = element.getSchemaTypeName();
            String baseType = resolveBaseType(typeName);
            metadata.setBaseType(baseType);
            
            // If it's a complex type, process it
            XmlSchemaType type = schemaCollection.getTypeByQName(typeName);
            if (type instanceof XmlSchemaComplexType) {
                processComplexType((XmlSchemaComplexType) type, metadata);
            }
        }
        
        return metadata;
    }
    
    private void processComplexType(XmlSchemaComplexType complexType, XsdMetadata metadata) {
        // Check if we've already processed this type to avoid infinite recursion
        QName typeName = complexType.getQName();
        if (typeName != null && processedTypes.containsKey(typeName)) {
            XsdMetadata cached = processedTypes.get(typeName);
            metadata.setAttributes(new HashMap<>(cached.getAttributes()));
            metadata.setChildElements(new HashMap<>(cached.getChildElements()));
            metadata.setBaseType(cached.getBaseType());
            return;
        }
        
        if (typeName != null) {
            processedTypes.put(typeName, metadata);
        }
        
        // Process base type if this is an extension or restriction
        XmlSchemaContent content = complexType.getContentModel();
        if (content != null) {
            if (content instanceof XmlSchemaComplexContentExtension) {
                XmlSchemaComplexContentExtension extension = (XmlSchemaComplexContentExtension) content;
                String baseType = resolveBaseType(extension.getBaseTypeName());
                metadata.setBaseType(baseType);
                
                // Process base type attributes and elements
                XmlSchemaType baseSchemaType = schemaCollection.getTypeByQName(extension.getBaseTypeName());
                if (baseSchemaType instanceof XmlSchemaComplexType) {
                    processComplexType((XmlSchemaComplexType) baseSchemaType, metadata);
                }
                
                // Process extension particle
                if (extension.getParticle() != null) {
                    processParticle(extension.getParticle(), metadata);
                }
                
                // Process extension attributes
                for (XmlSchemaAttributeOrGroupRef attr : extension.getAttributes()) {
                    if (attr instanceof XmlSchemaAttribute) {
                        processAttribute((XmlSchemaAttribute) attr, metadata);
                    }
                }
            } else if (content instanceof XmlSchemaComplexContentRestriction) {
                XmlSchemaComplexContentRestriction restriction = (XmlSchemaComplexContentRestriction) content;
                String baseType = resolveBaseType(restriction.getBaseTypeName());
                metadata.setBaseType(baseType);
                
                // Process restriction particle
                if (restriction.getParticle() != null) {
                    processParticle(restriction.getParticle(), metadata);
                }
                
                // Process restriction attributes
                for (XmlSchemaAttributeOrGroupRef attr : restriction.getAttributes()) {
                    if (attr instanceof XmlSchemaAttribute) {
                        processAttribute((XmlSchemaAttribute) attr, metadata);
                    }
                }
            } else if (content instanceof XmlSchemaSimpleContentExtension) {
                XmlSchemaSimpleContentExtension extension = (XmlSchemaSimpleContentExtension) content;
                String baseType = resolveBaseType(extension.getBaseTypeName());
                metadata.setBaseType(baseType);
                
                // Process extension attributes
                for (XmlSchemaAttributeOrGroupRef attr : extension.getAttributes()) {
                    if (attr instanceof XmlSchemaAttribute) {
                        processAttribute((XmlSchemaAttribute) attr, metadata);
                    }
                }
            }
        } else {
            // No content model, process particle directly
            XmlSchemaParticle particle = complexType.getParticle();
            if (particle != null) {
                processParticle(particle, metadata);
            }
        }
        
        // Process attributes
        for (XmlSchemaAttributeOrGroupRef attr : complexType.getAttributes()) {
            if (attr instanceof XmlSchemaAttribute) {
                processAttribute((XmlSchemaAttribute) attr, metadata);
            }
        }
    }
    
    private void processSimpleType(XmlSchemaSimpleType simpleType, XsdMetadata metadata) {
        XmlSchemaSimpleTypeContent content = simpleType.getContent();
        
        if (content instanceof XmlSchemaSimpleTypeRestriction) {
            XmlSchemaSimpleTypeRestriction restriction = (XmlSchemaSimpleTypeRestriction) content;
            String baseType = resolveBaseType(restriction.getBaseTypeName());
            metadata.setBaseType(baseType);
        } else if (content instanceof XmlSchemaSimpleTypeList) {
            XmlSchemaSimpleTypeList list = (XmlSchemaSimpleTypeList) content;
            String itemType = resolveBaseType(list.getItemTypeName());
            metadata.setBaseType("list of " + itemType);
        } else if (content instanceof XmlSchemaSimpleTypeUnion) {
            metadata.setBaseType("union");
        }
    }
    
    private void processParticle(XmlSchemaParticle particle, XsdMetadata parent) {
        if (particle instanceof XmlSchemaSequence) {
            XmlSchemaSequence sequence = (XmlSchemaSequence) particle;
            for (XmlSchemaSequenceMember member : sequence.getItems()) {
                if (member instanceof XmlSchemaElement) {
                    XsdMetadata childMetadata = processElement((XmlSchemaElement) member);
                    if (childMetadata != null && childMetadata.getName() != null) {
                        parent.addChildElement(childMetadata.getName(), childMetadata);
                    }
                } else if (member instanceof XmlSchemaGroupRef) {
                    processGroupRef((XmlSchemaGroupRef) member, parent);
                }
            }
        } else if (particle instanceof XmlSchemaChoice) {
            XmlSchemaChoice choice = (XmlSchemaChoice) particle;
            for (XmlSchemaChoiceMember member : choice.getItems()) {
                if (member instanceof XmlSchemaElement) {
                    XsdMetadata childMetadata = processElement((XmlSchemaElement) member);
                    if (childMetadata != null && childMetadata.getName() != null) {
                        parent.addChildElement(childMetadata.getName(), childMetadata);
                    }
                }
            }
        } else if (particle instanceof XmlSchemaAll) {
            XmlSchemaAll all = (XmlSchemaAll) particle;
            for (XmlSchemaAllMember member : all.getItems()) {
                if (member instanceof XmlSchemaElement) {
                    XsdMetadata childMetadata = processElement((XmlSchemaElement) member);
                    if (childMetadata != null && childMetadata.getName() != null) {
                        parent.addChildElement(childMetadata.getName(), childMetadata);
                    }
                }
            }
        }
    }
    
    private void processGroupRef(XmlSchemaGroupRef groupRef, XsdMetadata parent) {
        XmlSchemaGroup group = schemaCollection.getGroupByQName(groupRef.getRefName());
        if (group != null && group.getParticle() != null) {
            processParticle(group.getParticle(), parent);
        }
    }
    
    private void processAttribute(XmlSchemaAttribute attribute, XsdMetadata parent) {
        XsdMetadata attrMetadata = new XsdMetadata();
        attrMetadata.setElementType("attribute");
        attrMetadata.setName(attribute.getName());
        
        // Get attribute type
        XmlSchemaSimpleType attrType = attribute.getSchemaType();
        if (attrType != null) {
            processSimpleType(attrType, attrMetadata);
        } else if (attribute.getSchemaTypeName() != null) {
            String baseType = resolveBaseType(attribute.getSchemaTypeName());
            attrMetadata.setBaseType(baseType);
        }
        
        if (attrMetadata.getName() != null) {
            parent.addAttribute(attrMetadata.getName(), attrMetadata);
        }
    }
    
    private String resolveBaseType(QName typeName) {
        if (typeName == null) {
            return null;
        }
        
        // Check if it's an XSD built-in type
        if (XSD_NAMESPACE.equals(typeName.getNamespaceURI())) {
            return "xs:" + typeName.getLocalPart();
        }
        
        // Try to resolve custom types to their base XSD types
        XmlSchemaType type = schemaCollection.getTypeByQName(typeName);
        if (type instanceof XmlSchemaSimpleType) {
            XmlSchemaSimpleType simpleType = (XmlSchemaSimpleType) type;
            XmlSchemaSimpleTypeContent content = simpleType.getContent();
            
            if (content instanceof XmlSchemaSimpleTypeRestriction) {
                XmlSchemaSimpleTypeRestriction restriction = (XmlSchemaSimpleTypeRestriction) content;
                return resolveBaseType(restriction.getBaseTypeName());
            }
        }
        
        // Return the type name as is
        return typeName.getLocalPart();
    }
    
    // Utility method to print the parsed structure
    public void printStructure(XsdMetadata metadata, String indent) {
        System.out.println(indent + metadata);
        
        // Print attributes
        for (Map.Entry<String, XsdMetadata> entry : metadata.getAttributes().entrySet()) {
            System.out.println(indent + "  @" + entry.getKey() + " : " + entry.getValue().getBaseType());
        }
        
        // Print child elements
        for (Map.Entry<String, XsdMetadata> entry : metadata.getChildElements().entrySet()) {
            printStructure(entry.getValue(), indent + "  ");
        }
    }
    
    // Main method for testing
    public static void main(String[] args) {
        try {
            XsdParser parser = new XsdParser();
            XsdMetadata rootMetadata = parser.parseXsd("path/to/your/main.xsd");
            
            System.out.println("Parsed XSD Structure:");
            parser.printStructure(rootMetadata, "");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

/* 
Maven Dependencies:
Add this to your pom.xml:

<dependency>
    <groupId>org.apache.ws.xmlschema</groupId>
    <artifactId>xmlschema-core</artifactId>
    <version>2.3.0</version>
</dependency>

Or for Gradle:
implementation 'org.apache.ws.xmlschema:xmlschema-core:2.3.0'
*/
