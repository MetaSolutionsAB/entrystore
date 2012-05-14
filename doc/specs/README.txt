README : 

*** HOW TO GENERATE HTML ONTOLOGY SPECS FROM ONTOLOGY .RDFS FILE *****


#1. Install specgen.py script
Instructions for downloading and installing this file can be found there:
http://forge.morfeo-project.org/wiki_en/index.php/SpecGen

Be sure your python installation support the following dependencies:
	Python interpreter (python>=2.4)
	Python bindings for the Redland RDF library (python-librdf>=1.0) 

#2. Edit the template
Edit the template-terms.html as regular HTML, except the $s parts which will be replaced with automatically generated stuff.

#3. Command line
$ ./specgen.py ../terms.rdf entry template-terms.html terms.html

In general, to generate the HTML spec file you use:
specgen ontology.rdfs namespace template.html output.html
with:
  specgen: the name of the command
  ontology.rdfs: the path to the ontology source file
  namespace: the namespace shortcut
  template.html: the html template file
  output.html: the name of the destination file
  
## RMQ ##
- for the script to work, you have to have a meta info part in the ontology. Something like:
    <owl:Ontology rdf:about="http://scam.sf.net/schema#">
      <dcterms:title xml:lang="en">ReM3 Model</dcterms:title>
      <owl:versionInfo>version 0.1</owl:versionInfo>
      <dcterms:description xml:lang="en">ReM2 Model used for the EntryStore
      system.</dcterms:description>
      <rdfs:seeAlso rdf:resource="http://code.google.com/p/entrystore/"
      rdfs:label="EntryStore project Homepage"/>
    </owl:Ontology>
	
Problem is that it is not compatible with OWL Lite, so it is commented out. Just put it back before compiling the spec file.