import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;
import com.sun.xml.xsom.util.DomAnnotationParserFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
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
    private boolean isRequired;
    
    public XsdMetadata() {
        this.attributes = new HashMap<>();
        this.childElements = new HashMap<>();
        this.minOccurs = "1";
        this.maxOccurs = "1";
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
    
    public boolean isRequired() { return isRequired; }
    public void setRequired(boolean required) { isRequired = required; }
    
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
                ", required=" + isRequired +
                ", attributes=" + attributes.keySet() +
                ", childElements=" + childElements.keySet() +
                '}';
    }
}

// Main XSD Parser class using XSOM
public class XsdParser {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private XSSchemaSet schemaSet;
    private Set<XSType> processedTypes;
    
    public XsdParser() {
        this.processedTypes = new HashSet<>();
    }
    
    public XsdMetadata parseXsd(String xsdFilePath) throws Exception {
        File xsdFile = new File(xsdFilePath);
        
        // Create XSOM parser
        XSOMParser parser = new XSOMParser(SAXParserFactory.newInstance());
        parser.setAnnotationParser(new DomAnnotationParserFactory());
        
        // Parse the XSD file - this will automatically handle includes and imports
        parser.parse(xsdFile);
        schemaSet = parser.getResult();
        
        if (schemaSet == null) {
            throw new Exception("Failed to parse XSD file");
        }
        
        // Create root metadata
        XsdMetadata rootMetadata = new XsdMetadata();
        rootMetadata.setElementType("root");
        rootMetadata.setName("XSD_ROOT");
        
        // Process all schemas in the set
        for (XSSchema schema : schemaSet.getSchemas()) {
            // Skip the XSD namespace schema itself
            if (XSD_NAMESPACE.equals(schema.getTargetNamespace())) {
                continue;
            }
            
            // Process all global elements
            for (XSElementDecl element : schema.getElementDecls().values()) {
                XsdMetadata elementMetadata = processElement(element, null);
                if (elementMetadata != null && elementMetadata.getName() != null) {
                    rootMetadata.addChildElement(elementMetadata.getName(), elementMetadata);
                }
            }
        }
        
        return rootMetadata;
    }
    
    private XsdMetadata processElement(XSElementDecl element, XSParticle particle) {
        XsdMetadata metadata = new XsdMetadata();
        metadata.setElementType("element");
        metadata.setName(element.getName());
        
        // Set occurrence information from particle if available
        if (particle != null) {
            metadata.setMinOccurs(String.valueOf(particle.getMinOccurs()));
            metadata.setMaxOccurs(particle.getMaxOccurs().intValue() == XSParticle.UNBOUNDED ? 
                                 "unbounded" : String.valueOf(particle.getMaxOccurs()));
        }
        
        // Get the element's type
        XSType type = element.getType();
        
        if (type.isSimpleType()) {
            processSimpleType(type.asSimpleType(), metadata);
        } else if (type.isComplexType()) {
            processComplexType(type.asComplexType(), metadata);
        }
        
        return metadata;
    }
    
    private void processComplexType(XSComplexType complexType, XsdMetadata metadata) {
        // Avoid infinite recursion
        if (processedTypes.contains(complexType)) {
            metadata.setBaseType(complexType.getName() != null ? complexType.getName() : "complex");
            return;
        }
        processedTypes.add(complexType);
        
        // Check if this complex type extends or restricts another type
        XSType baseType = complexType.getBaseType();
        if (baseType != null && !baseType.getName().equals("anyType")) {
            String resolvedBaseType = resolveBaseType(baseType);
            metadata.setBaseType(resolvedBaseType);
        }
        
        // Process content type
        XSContentType contentType = complexType.getContentType();
        if (contentType != null) {
            XSParticle particle = contentType.asParticle();
            if (particle != null) {
                processParticle(particle, metadata);
            } else if (contentType.asSimpleType() != null) {
                // Complex type with simple content
                processSimpleType(contentType.asSimpleType(), metadata);
            }
        }
        
        // Process attributes
        Collection<? extends XSAttributeUse> attributeUses = complexType.getAttributeUses();
        for (XSAttributeUse attrUse : attributeUses) {
            processAttributeUse(attrUse, metadata);
        }
        
        processedTypes.remove(complexType);
    }
    
    private void processSimpleType(XSSimpleType simpleType, XsdMetadata metadata) {
        String baseType = resolveSimpleType(simpleType);
        metadata.setBaseType(baseType);
    }
    
    private void processParticle(XSParticle particle, XsdMetadata parent) {
        XSTerm term = particle.getTerm();
        
        if (term.isElementDecl()) {
            XSElementDecl element = term.asElementDecl();
            XsdMetadata childMetadata = processElement(element, particle);
            if (childMetadata != null && childMetadata.getName() != null) {
                parent.addChildElement(childMetadata.getName(), childMetadata);
            }
        } else if (term.isModelGroup()) {
            XSModelGroup modelGroup = term.asModelGroup();
            processModelGroup(modelGroup, parent);
        } else if (term.isModelGroupDecl()) {
            XSModelGroupDecl groupDecl = term.asModelGroupDecl();
            processModelGroup(groupDecl.getModelGroup(), parent);
        }
    }
    
    private void processModelGroup(XSModelGroup modelGroup, XsdMetadata parent) {
        // Process all particles in the model group (sequence, choice, all)
        for (XSParticle particle : modelGroup.getChildren()) {
            processParticle(particle, parent);
        }
    }
    
    private void processAttributeUse(XSAttributeUse attrUse, XsdMetadata parent) {
        XSAttributeDecl attr = attrUse.getDecl();
        
        XsdMetadata attrMetadata = new XsdMetadata();
        attrMetadata.setElementType("attribute");
        attrMetadata.setName(attr.getName());
        attrMetadata.setRequired(attrUse.isRequired());
        
        // Get attribute type
        XSSimpleType attrType = attr.getType();
        if (attrType != null) {
            processSimpleType(attrType, attrMetadata);
        }
        
        if (attrMetadata.getName() != null) {
            parent.addAttribute(attrMetadata.getName(), attrMetadata);
        }
    }
    
    private String resolveBaseType(XSType type) {
        if (type == null) {
            return null;
        }
        
        // If it's a simple type, resolve it
        if (type.isSimpleType()) {
            return resolveSimpleType(type.asSimpleType());
        }
        
        // If it's a complex type with simple content, get the simple type
        if (type.isComplexType()) {
            XSComplexType complexType = type.asComplexType();
            XSContentType contentType = complexType.getContentType();
            if (contentType != null && contentType.asSimpleType() != null) {
                return resolveSimpleType(contentType.asSimpleType());
            }
        }
        
        // Check if it's an XSD built-in type
        if (type.getTargetNamespace() != null && 
            type.getTargetNamespace().equals(XSD_NAMESPACE)) {
            return "xs:" + type.getName();
        }
        
        return type.getName() != null ? type.getName() : "complex";
    }
    
    private String resolveSimpleType(XSSimpleType simpleType) {
        // Check if it's already a built-in XSD type
        if (simpleType.getTargetNamespace() != null && 
            simpleType.getTargetNamespace().equals(XSD_NAMESPACE)) {
            return "xs:" + simpleType.getName();
        }
        
        // Check if it's a restriction
        XSRestrictionSimpleType restriction = simpleType.asRestriction();
        if (restriction != null) {
            return resolveSimpleType(restriction.getBaseType());
        }
        
        // Check if it's a list
        XSListSimpleType list = simpleType.asList();
        if (list != null) {
            return "list of " + resolveSimpleType(list.getItemType());
        }
        
        // Check if it's a union
        XSUnionSimpleType union = simpleType.asUnion();
        if (union != null) {
            return "union";
        }
        
        return simpleType.getName() != null ? simpleType.getName() : "unknown";
    }
    
    // Utility method to print the parsed structure
    public void printStructure(XsdMetadata metadata, String indent) {
        System.out.println(indent + metadata);
        
        // Print attributes
        for (Map.Entry<String, XsdMetadata> entry : metadata.getAttributes().entrySet()) {
            System.out.println(indent + "  @" + entry.getKey() + 
                             " : " + entry.getValue().getBaseType() +
                             (entry.getValue().isRequired() ? " (required)" : " (optional)"));
        }
        
        // Print child elements
        for (Map.Entry<String, XsdMetadata> entry : metadata.getChildElements().entrySet()) {
            printStructure(entry.getValue(), indent + "  ");
        }
    }
    
    // Get all element paths (useful for understanding schema structure)
    public void printAllPaths(XsdMetadata metadata, String currentPath, Set<String> visited) {
        String fullPath = currentPath.isEmpty() ? metadata.getName() : currentPath + "/" + metadata.getName();
        
        if (visited.contains(fullPath)) {
            return; // Avoid infinite recursion
        }
        visited.add(fullPath);
        
        if (metadata.getChildElements().isEmpty()) {
            System.out.println(fullPath + " : " + metadata.getBaseType());
        }
        
        for (XsdMetadata child : metadata.getChildElements().values()) {
            printAllPaths(child, fullPath, visited);
        }
    }
    
    // Main method for testing
    public static void main(String[] args) {
        try {
            XsdParser parser = new XsdParser();
            XsdMetadata rootMetadata = parser.parseXsd("path/to/your/schema.xsd");
            
            System.out.println("Parsed XSD Structure:");
            parser.printStructure(rootMetadata, "");
            
            System.out.println("\nAll Element Paths:");
            parser.printAllPaths(rootMetadata, "", new HashSet<>());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

/* 
Maven Dependencies:
Add this to your pom.xml:

<dependency>
    <groupId>com.sun.xsom</groupId>
    <artifactId>xsom</artifactId>
    <version>20140925</version>
</dependency>

Or for Gradle:
implementation 'com.sun.xsom:xsom:20140925'

Note: You might need to add the following repository if the artifact is not found:
<repository>
    <id>maven2-repository.java.net</id>
    <name>Java.net Repository for Maven</name>
    <url>https://maven.java.net/content/repositories/public/</url>
</repository>
*/
