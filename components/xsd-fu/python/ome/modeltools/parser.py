import xml.etree.ElementTree as ET

class Attribute:
    def __init__(self, element, name, xmlelement):
        self.element = element
        self.name = name
        self.xmlelement = xmlelement

        self.attribute_type = xmlelement.attrib['type']
        self.use = xmlelement.attrib['use']

    def uri(self):
        return self.schema.uri()

class ElementReference:
    def __init__(self, element, xmlelement):
        self.schema = schema
        self.name = name
        self.xmlelement = xmlelement
        self.attribute_map = {}

    def process_attributes(self):
        print "H1: ATTRS for %s" % (self.name)
        for attr in self.xmlelement.findall('./{http://www.w3.org/2001/XMLSchema}complexType/{http://www.w3.org/2001/XMLSchema}attribute'):
            print "H2"
            name = attr.attrib['name']
            print "PARSE_ATTR1 %s" % (name)
            newattrib = Attribute(self, name, attr)
            
            self.add_attribute(newattr)
        
    def add_attribute(self, attribute):
        if attribute.name not in self.attribute_map.keys():
            self.attribute_map[attribute.name] = attribute

class Element:
    def __init__(self, schema, name, xmlelement):
        self.schema = schema
        self.name = name
        self.xmlelement = xmlelement
        self.attribute_map = {}

    def uri(self):
        return self.schema.uri()

    def process_attributes(self):
        print "H1: ATTRS for %s" % (self.name)
        for attr in self.xmlelement.findall('{http://www.w3.org/2001/XMLSchema}complexType/{http://www.w3.org/2001/XMLSchema}attribute'):
            print "H2"
            name = attr.attrib['name']
            print "PARSE_ATTR1 %s" % (name)
            newattrib = Attribute(self, name, attr)
            
            self.add_attribute(newattrib)
        
    def add_attribute(self, attribute):
        if attribute.name not in self.attribute_map.keys():
            self.attribute_map[attribute.name] = attribute

class Schema:
    def __init__(self, schemaset, filename):
        self.schemaset = schemaset
        self.filename = filename
        self.tree = ET.parse(filename)
        self.root = self.tree.getroot()
        self.element_map = {}

    def uri(self):
        return self.root.attrib['targetNamespace']

    def process_imports(self):
        # Find and validate all xsd:import elements; throw if any import is missing
        for imp in self.root.findall('{http://www.w3.org/2001/XMLSchema}import'):
            ns = imp.attrib['namespace']
            print "IMPORT %s" % (ns)
            try:
                self.schemaset.schema(ns)
            except KeyError as e:
                if ns != 'http://www.w3.org/XML/1998/namespace':
                    print "XML schema for namespace %s not registered" % (ns)
                    raise e

    def process_elements(self):
        # Find all xml:element elements
        for elem in self.root.findall('.//{http://www.w3.org/2001/XMLSchema}element'):
            print "PARSE_ELEMENT1 %s = %s" % (elem.tag, ",".join(elem.keys()))
            # Only process real elements, not references.
            if 'name' in elem.attrib:
                name = elem.attrib['name']
                newelem = Element(self, name, elem)            
                self.add_element(newelem)

    def process_attributes(self):
        # Add element attributes
        for elem in self.element_map.values():
            elem.process_attributes()

    def add_element(self, element):
        if element.name not in self.element_map.keys():
            self.element_map[element.name] = element
            self.schemaset.add_element(element)

class SchemaSet:
    def __init__(self, filenames):
        self.schema_map = {}
        self.element_map = {}

        print "NEWSCHEMA: Adding %s" % (', '.join(filenames))
        for filename in filenames:
            self.add_schema(filename)

    def add_schema(self, filename):
        newschema = Schema(self, filename)
        newuri = newschema.uri()
        if newuri not in self.schema_map.keys():
            self.schema_map[newuri] = newschema
        else:
            print "Skipping %s (%s): Schema already registered as %s" % \
                (filename, newuri, schema(newuri).filename)

    def process(self):
        for schema in self.schema_map.values():
            schema.process_imports()
        for schema in self.schema_map.values():
            schema.process_elements()
        for schema in self.schema_map.values():
            schema.process_attributes()

    def schema(self, uri):
        return self.schema_map[uri]

    def add_element(self, element):
        if element.name not in self.element_map.keys():
            self.element_map[element.name] = element
