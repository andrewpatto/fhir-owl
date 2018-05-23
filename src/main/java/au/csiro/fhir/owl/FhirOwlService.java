/**
 * Copyright CSIRO Australian e-Health Research Centre (http://aehrc.com). All rights reserved. Use is subject to 
 * license terms and conditions.
 */

package au.csiro.fhir.owl;

import ca.uhn.fhir.context.FhirContext;
import com.google.common.base.Optional;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.PostConstruct;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hl7.fhir.dstu3.model.BooleanType;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.dstu3.model.Bundle.BundleType;
import org.hl7.fhir.dstu3.model.Bundle.HTTPVerb;
import org.hl7.fhir.dstu3.model.CodeSystem;
import org.hl7.fhir.dstu3.model.CodeSystem.CodeSystemContentMode;
import org.hl7.fhir.dstu3.model.CodeSystem.CodeSystemHierarchyMeaning;
import org.hl7.fhir.dstu3.model.CodeSystem.ConceptDefinitionComponent;
import org.hl7.fhir.dstu3.model.CodeSystem.ConceptDefinitionDesignationComponent;
import org.hl7.fhir.dstu3.model.CodeSystem.ConceptPropertyComponent;
import org.hl7.fhir.dstu3.model.CodeSystem.FilterOperator;
import org.hl7.fhir.dstu3.model.CodeSystem.PropertyComponent;
import org.hl7.fhir.dstu3.model.CodeSystem.PropertyType;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.SimpleIRIMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Main service.
 * 
 * @author Alejandro Metke Jimenez
 *
 */
@Service
public class FhirOwlService {
  
  private static final Log log = LogFactory.getLog(FhirOwlService.class);
  
  @Value("#{'${ontoserver.owl.publisher}'.split(',')}")
  private List<String> publisherElems;

  @Value("#{'${ontoserver.owl.description}'.split(',')}")
  private List<String> descriptionElems;
  
  @Autowired
  private FhirContext ctx;
  
  private final Map<IRI, IRI> iriMap = new HashMap<>();
  
  @PostConstruct
  private void init() {
    log.info("Checking for IRI mappings");
    InputStream input = null;
    try {
      input = FhirContext.class.getClassLoader().getResourceAsStream("iri_mappings.txt");
      if (input == null) {
        log.info("Did not find iri_mappings.txt in classpath.");
        return;
      }
      
      final String[] lines = getLinesFromInputStream(input);
      for (String line : lines) {
        String[] parts = line.split("[,]");
        iriMap.put(IRI.create(parts[0]), IRI.create(new File(parts[1])));
      }
      
      for (IRI key : iriMap.keySet()) {
        log.info("Loaded IRI mapping " + key.toString() + " -> " + iriMap.get(key).toString());
      }
      
    } catch (Throwable t) {
      log.warn("There was a problem loading IRI mappings.", t);
    }
  }
  
  /**
   * Transforms an OWL file into a bundle of FHIR code systems.
   * 
   * @param input The input OWL file.
   * @param output The output FHIR bundle.
   * @throws IOException If there is an I/O issue.
   * @throws OWLOntologyCreationException If there is a problem creating the ontology.
   */
  public void transform(File input, File output) throws IOException, OWLOntologyCreationException {
    log.info("Creating code systems");
    final List<CodeSystem> codeSystems = createCodeSystems(input);
    final Bundle b = new Bundle();
    b.setType(BundleType.BATCH);
    for (CodeSystem cs : codeSystems) {
      int numConcepts = cs.getConcept().size();
      if (numConcepts > 0) {
        final String name = cs.getName();
        String id = null;
        log.info("Adding code system " + name + " [" + numConcepts + "]");
        if (name.contains("/")) {
          id = IRI.create(name).getShortForm().replaceAll("[^a-zA-Z0-9]", "-");
        } else {
          id = name.replaceAll("[^a-zA-Z0-9]", "-");
        }
        
        BundleEntryComponent bec = b.addEntry();
        bec.setResource(cs);
        bec.getRequest().setMethod(HTTPVerb.PUT).setUrl("CodeSystem/" + id);
      } else {
        log.info("Excluding code system " + cs.getName() + " because it has no codes");
      }
    }
    
    try (BufferedWriter bw = new BufferedWriter(new FileWriter(output))) {
      log.info("Writing bundle to file: " + output.getAbsolutePath());
      ctx.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(b, bw);
      log.info("Done!");
    }
  }
  
  private String[] getLinesFromInputStream(InputStream is) throws IOException {
    final List<String> res = new ArrayList<>();
    BufferedReader br = null;
    String line;
    try {
      br = new BufferedReader(new InputStreamReader(is));
      while ((line = br.readLine()) != null) {
        res.add(line);
      }
    } finally {
      if (br != null) {
        try {
          br.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return res.toArray(new String[res.size()]);
  }
  
  private void addIriMappings(OWLOntologyManager manager) {
    for (IRI key : iriMap.keySet()) {
      manager.getIRIMappers().add(new SimpleIRIMapper(key, iriMap.get(key)));
    }
  }

  private String getOntologiesNames(Set<OWLOntology> onts) {
    StringBuilder sb = new StringBuilder();
    for (OWLOntology ont : onts) {
      sb.append(getOntologyName(ont));
      sb.append("\n");
    }
    return sb.toString();
  }
  
  /**
   * Creates code systems for an ontology and its imports.
   * 
   * @param input The ontology to transform.
   * @return A list of generated code systems.
   * @throws OWLOntologyCreationException If something goes wrong creating the ontologies.
   */
  private List<CodeSystem> createCodeSystems(File input) 
      throws OWLOntologyCreationException {
    
    log.info("Loading ontology from file " + input.getAbsolutePath());
    OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    addIriMappings(manager);
    final OWLOntology rootOnt = manager.loadOntologyFromOntologyDocument(input);
    
    // 1. Get IRI -> system map - might contain null values
    Set<OWLOntology> closure = manager.getImportsClosure(rootOnt);
    final Map<IRI, String> iriSystemMap = getIriSystemMap(closure);
    
    // 2. Classify root ontology
    log.info("Classifying ontology " + getOntologyName(rootOnt));
    OWLReasonerFactory reasonerFactory = new ElkReasonerFactory();
    OWLReasoner reasoner = reasonerFactory.createReasoner(rootOnt);
    reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
    
    // 2. For each ontology create a code system
    final List<CodeSystem> res = new ArrayList<>();
    for (OWLOntology ont : closure) {
      log.info("Creating code system for ontology " + getOntologyName(ont));
      try {
        res.add(createCodeSystem(ont, manager.getOWLDataFactory(), reasoner, iriSystemMap));
      } catch (NoIdException e) {
        log.warn("Could not create a Code System for ontology " + getOntologyName(ont) 
            + " because it has no IRI.");
      }
      reasoner.dispose();
    }

    return res;
  }
  
  /**
   * Iterates over all the concepts in the closure of an ontology an attempts to determine
   * the system for each one.
   * 
   * @param rootOnt The root ontology.
   * @param manager The ontology manager.
   * @param closure Used to return the ontology closure.
   * @return A map of IRIs to systems.
   * 
   * @throws OWLOntologyCreationException If something goes wrong creating the ontologies.
   */
  private Map<IRI, String> getIriSystemMap(Set<OWLOntology> closure) {
    log.info("Getting IRI -> system map for ontologies: \n" + getOntologiesNames(closure));
    
    // 1. For each ontology, check IRI, create OBO-like prefix and build prefix -> system map
    log.info("Building prefix -> system map");
    final Map<String, String> prefixSystemMap = new HashMap<>();
    for (OWLOntology ont : closure) {
      final IRI iri = getOntologyIri(ont);
      if (iri != null) {
        final String shortForm = iri.getShortForm();
        if (shortForm.endsWith(".owl")) {
          log.info("Found OBO-like IRI: " + iri);
          prefixSystemMap.put(shortForm.substring(0, shortForm.length() - 4).toLowerCase(), 
              iri.toString());
        } else {
          log.info("IRI is not OBO-like:" + iri);
        }
      } else {
        log.warn("Ontology " + getOntologyName(ont) + " has no IRI.");
      }
    }
    
    // 3. Create a IRI -> system map for all classes in the closure
    log.info("Building IRI -> system map");
    final Map<IRI, String> iriSystemMap = new HashMap<>();
    for (OWLOntology ont : closure) {
      final IRI ontIri = getOntologyIri(ont);
      final String ontologyIri = ontIri != null ? ontIri.toString() : "ANONYMOUS";
      for (OWLClass owlClass : ont.getClassesInSignature(Imports.EXCLUDED)) {
        final IRI classIri = owlClass.getIRI();
        String system = getSystem(owlClass, ontologyIri, prefixSystemMap);
        if (system == null) {
          // If the system cannot be determined we assume the system is the ontology where
          // the concept is declared
          iriSystemMap.put(classIri, ontologyIri);
        } else {
          iriSystemMap.put(classIri, system);
        }
      }
    }

    return iriSystemMap;
  }

  /**
   * Creates a code system from an ontology.
   * 
   * @param ont The ontology.
   * @param factory The OWL factory.
   * @param reasoner The OWL reasoner.
   * @param iriSystemMap  
   * 
   * @return The code system.
   */
  private CodeSystem createCodeSystem(OWLOntology ont, final OWLDataFactory factory, 
      OWLReasoner reasoner, Map<IRI, String> iriSystemMap) {
    // Extract ontology information
    final String codeSystemUrl;
    final String codeSystemVersion;

    final OWLOntologyID ontId = ont.getOntologyID();
    final Optional<IRI> iri = ontId.getOntologyIRI();
    final Optional<IRI> version = ontId.getVersionIRI();

    if (iri.isPresent()) {
      codeSystemUrl = iri.get().toString();
    } else {
      throw new NoIdException();
    }

    if (version.isPresent()) {
      codeSystemVersion = version.get().toString();
    } else {
      codeSystemVersion = "NA";
    }
    
    String codeSystemName = codeSystemUrl;
    String publisher = null;
    String description = null;
    
    // Index annotations
    final Map<String, List<String>> annMap = new HashMap<>();

    for (OWLAnnotation ann : ont.getAnnotations()) {
      final String prop = ann.getProperty().getIRI().toString();
      Optional<OWLLiteral> val = ann.getValue().asLiteral();
      if (val.isPresent()) {
        List<String> vals = annMap.get(prop);
        if (vals == null) {
          vals = new ArrayList<>();
          annMap.put(prop, vals);
        }
        vals.add(val.get().getLiteral());
      }
    }

    if (annMap.containsKey("http://www.w3.org/2000/01/rdf-schema#label")) {
      // This is the name of the ontology
      codeSystemName = annMap.get("http://www.w3.org/2000/01/rdf-schema#label").get(0);
    }

    for (String publisherElem : publisherElems) {
      if (annMap.containsKey(publisherElem)) {
        // This is the publisher of the ontology
        // Get first publisher - FHIR spec only supports one
        publisher = annMap.get(publisherElem).get(0); 
        break;
      }
    }

    for (String descriptionElem : descriptionElems) {
      if (annMap.containsKey(descriptionElem)) {
        // This is the description of the ontology
        description = annMap.get(descriptionElem).get(0);
        break;
      }
    }
    
    // Determine if ontology is derived to set the content attribute
    final IRI sourceIri = getSource(ont);

    // Populate basic code system info
    final CodeSystem cs = new CodeSystem();
    cs.setUrl(codeSystemUrl);
    cs.setVersion(codeSystemVersion);
    cs.setName(codeSystemName);
    if (publisher != null) { 
      cs.setPublisher(publisher);
    }
    if (description != null) {
      cs.setDescription(description);
    }
    cs.setStatus(PublicationStatus.ACTIVE);
    // Create default value set
    cs.setValueSet(codeSystemUrl);
    cs.setHierarchyMeaning(CodeSystemHierarchyMeaning.ISA);
    
    boolean derived = sourceIri != null;
    if (derived) {
      cs.setContent(CodeSystemContentMode.FRAGMENT);
    } else {
      cs.setContent(CodeSystemContentMode.COMPLETE);
    }

    PropertyComponent parentProp = cs.addProperty();
    parentProp.setCode("parent");
    parentProp.setType(PropertyType.CODE);
    parentProp.setDescription("Parent codes.");

    PropertyComponent rootProp = cs.addProperty();
    rootProp.setCode("root");
    rootProp.setType(PropertyType.BOOLEAN);
    rootProp.setDescription("Indicates if this concept is a root concept (i.e. Thing is "
        + "equivalent or a direct parent)");

    PropertyComponent depProp = cs.addProperty();
    depProp.setCode("deprecated");
    depProp.setType(PropertyType.BOOLEAN);
    depProp.setDescription("Indicates if this concept is deprecated.");
    
    // This property indicates if a concept is meant to represent a root, i.e. it's child of Thing.
    cs.addFilter().setCode("root").addOperator(FilterOperator.EQUAL).setValue("True or false.");
    cs.addFilter().setCode("deprecated").addOperator(FilterOperator.EQUAL)
      .setValue("True or false.");

    for (OWLClass owlClass : ont.getClassesInSignature(Imports.EXCLUDED)) {
      processClass(owlClass, cs, ont, reasoner, iriSystemMap);
    }

    return cs;
  }

  private boolean addHierarchyFields(final OWLReasoner reasoner, OWLClass owlClass, 
      ConceptDefinitionComponent cdc, boolean isRoot, Map<IRI, String> iriSystemMap) {
    // Add hierarchy-related fields
    final Set<OWLClass> parents = reasoner.getSuperClasses(owlClass, true).getFlattened();
    
    log.debug("Found " + parents.size() + " parents for concept " + owlClass.getIRI());
    for (OWLClass parent : parents) {
      if (parent.isOWLNothing()) { 
        continue;
      }
      final IRI iri = parent.getIRI();
      final String system = iriSystemMap.get(iri);
      if (system != null) {
        final ConceptPropertyComponent prop = cdc.addProperty();
        prop.setCode("parent");
        final String code = iri.getShortForm();
        prop.setValue(new Coding().setSystem(system).setCode(code));
      }
    }

    // Check if this concept is equivalent to Thing - in this case it is a root
    for (OWLClass eq : reasoner.getEquivalentClasses(owlClass)) {
      if (eq.isOWLThing()) {
        isRoot = true;
        break;
      }
    }
    return isRoot;
  }
  
  /**
   * Determines if an OWL class is deprecated based on annotations.
   * 
   * @param owlClass The OWL class.
   * @param ont The ontology it belongs to.
   * @return
   */
  private boolean isDeprecated(OWLClass owlClass, OWLOntology ont) {
    boolean isDeprecated = false;
    for (OWLAnnotation ann : EntitySearcher.getAnnotationObjects(owlClass, ont)) {
      OWLAnnotationProperty prop = ann.getProperty();
      if (prop != null && prop.getIRI().getShortForm().equals("deprecated")) {
        OWLAnnotationValue val = ann.getValue();
        if (val != null) {
          Optional<OWLLiteral> lit = val.asLiteral();
          if (lit.isPresent()) {
            final OWLLiteral l = lit.get();
            if (l.isBoolean()) {
              isDeprecated = l.parseBoolean();
            } else {
              log.warn("Found deprecated attribute but it is not boolean: " + l.toString());
            }
          }
        }
      }
    }
    return isDeprecated;
  }
  
  private String getPreferedTerm(OWLClass owlClass, OWLOntology ont) {
    final OWLDataFactory factory = ont.getOWLOntologyManager().getOWLDataFactory();
    for (OWLAnnotation a : EntitySearcher.getAnnotations(owlClass, ont, 
        factory.getRDFSLabel())) {
      OWLAnnotationValue val = a.getValue();
      if (val instanceof OWLLiteral) {
        return ((OWLLiteral) val).getLiteral();
      }
    }
    
    return null;
  }
  
  private Set<String> getSynonyms(OWLClass owlClass, OWLOntology ont, String preferredTerm) {
    final OWLDataFactory factory = ont.getOWLOntologyManager().getOWLDataFactory();
    
    final Set<String> synonyms = new HashSet<>();
    for (OWLAnnotation a : EntitySearcher.getAnnotations(owlClass, ont, 
        factory.getRDFSLabel())) {
      OWLAnnotationValue val = a.getValue();
      if (val instanceof OWLLiteral) {
        final String label = ((OWLLiteral) val).getLiteral();
        synonyms.add(label);
      }
    }
    
    for (OWLAnnotation ann : EntitySearcher.getAnnotationObjects(owlClass, ont)) {
      OWLAnnotationProperty prop = ann.getProperty();
      if (prop != null && prop.getIRI().getShortForm().equals("hasExactSynonym")) {
        // This is an oboInOwl extension
        OWLAnnotationValue val = ann.getValue();
        if (val != null) {
          Optional<OWLLiteral> lit = val.asLiteral();
          if (lit.isPresent()) {
            String label = lit.get().getLiteral();
            synonyms.add(label);
          }
        }
      }
    }
    
    synonyms.remove(preferredTerm);
    return synonyms;
  }

  /**
   * Returns the name of an ontology. Uses an rdfs:label or the ontology's IRI if one if not 
   * present. If the ontology has no IRI then it returns null.
   * 
   * @param ont The ontology.
   * @return The name of the ontology or null if it has no name.
   */
  private String getOntologyName(OWLOntology ont) {
    for (OWLAnnotation ann : ont.getAnnotations()) {
      final String prop = ann.getProperty().getIRI().toString();
      Optional<OWLLiteral> val = ann.getValue().asLiteral();
      if (val.isPresent() && "http://www.w3.org/2000/01/rdf-schema#label".equals(prop)) {
        return val.get().getLiteral();
      }
    }
    
    IRI iri = getOntologyIri(ont);
    
    return iri != null ? iri.toString() : null;
  }
  
  /**
   * If this ontology is derived from another ontology then it returns the source IRI.
   * Otherwise returns null.
   * 
   * @param ont The ontology.
   * @return The IRI of the source ontology or null if this ontology is not derived from
   *     another ontology.
   */
  private IRI getSource(OWLOntology ont) {
    for (OWLAnnotation ann : ont.getAnnotations()) {
      final String prop = ann.getProperty().getIRI().toString();
      Optional<IRI> val = ann.getValue().asIRI();
      if (val.isPresent() && "http://purl.org/dc/elements/1.1/source".equals(prop)) {
        return val.get();
      }
    }
    return null;
  }
  
  private IRI getOntologyIri(OWLOntology ont) {
    OWLOntologyID ontId = ont.getOntologyID();
    Optional<IRI> iri = ontId.getOntologyIRI();

    if (iri.isPresent()) {
      return iri.get();
    } else {
      return null;
    }
  }
  
  /**
   * Attempts to get the system for an OWL class. Assumes that the class uses the ontology's IRI as
   * prefix or that is uses the OBO naming conventions. Returns the system or null if unable to
   * find it.
   * 
   * @param owlClass The OWL class.
   * @param ontologyIri The IRI of the ontology where the class came from.
   * @param prefixSystemMap A map of OBO prefixes to systems.
   * @return The system or null if unable to find it.
   */
  private String getSystem(OWLClass owlClass, String ontologyIri, 
      Map<String, String> prefixSystemMap) {
    final IRI iri = owlClass.getIRI();
    final String fullIri = iri.toString();
    // Check if class IRI has ontology IRI as prefix
    if (fullIri.startsWith(ontologyIri)) {
      return ontologyIri;
    }
    
    // Check short name to see if it matches OBO conventions
    final String shortForm = iri.getShortForm();
    if (matchesOboConventions(shortForm)) {
      final String prefix = getOboPrefix(shortForm);
      return prefixSystemMap.get(prefix.toLowerCase());
    }
    return null;
  }
  
  private boolean matchesOboConventions(String shortForm) {
    return shortForm.matches("^[a-zA-Z]*[_][0-9]*");
  }
  
  private String getOboPrefix(String shortForm) {
    return shortForm.split("[_]")[0];
  }
  
  /**
   * Adds this concept to the code system.
   * 
   * @param owlClass The concept to add.
   * @param cs The code system where the concept will be added.
   * @param ont The ontology. Needed to search for the labels of the concept.
   * @param reasoner The reasoner. Required to get the parents of a concept.
   * @param iriSystemMap 
   * 
   */
  private void processClass(OWLClass owlClass, CodeSystem cs, OWLOntology ont, 
      OWLReasoner reasoner, Map<IRI, String> iriSystemMap) {
    final IRI iri = owlClass.getIRI();
    final String code = iri.getShortForm();
    final String classSystem = iriSystemMap.get(iri);
    
    final String codeSytemUrl = cs.getUrl();
    
    if (classSystem != null && classSystem.equals(codeSytemUrl)) {
      // This class is defined in this ontology so create code
      final ConceptDefinitionComponent cdc = new ConceptDefinitionComponent();
      cdc.setCode(code);

      final boolean isDeprecated = isDeprecated(owlClass, ont);

      // Special case: OWL:Thing
      if ("http://www.w3.org/2002/07/owl#Thing".equals(cdc.getCode())) {
        cdc.setDisplay("Thing");
      }

      boolean isRoot = false;
      isRoot = addHierarchyFields(reasoner, owlClass, cdc, isRoot, iriSystemMap);

      ConceptPropertyComponent prop = cdc.addProperty();
      prop.setCode("root");
      prop.setValue(new BooleanType(isRoot));

      prop = cdc.addProperty();
      prop.setCode("deprecated");
      prop.setValue(new BooleanType(isDeprecated));
      
      String preferredTerm = getPreferedTerm(owlClass, ont);
      final Set<String> synonyms = getSynonyms(owlClass, ont, preferredTerm);
      
      if (preferredTerm == null && synonyms.isEmpty()) {
        // No labels so display is just the code
        cdc.setDisplay(code);
      } else if (preferredTerm == null) {
        // No prefererd term but there are synonyms so pick any one as the display
        preferredTerm = synonyms.iterator().next();
        synonyms.remove(preferredTerm);
        
        cdc.setDisplay(preferredTerm);
        addSynonyms(synonyms, cdc);
      } else {
        cdc.setDisplay(preferredTerm);
        addSynonyms(synonyms, cdc);
      }
      
      cs.addConcept(cdc);
    }
  }
  
  private void addSynonyms(Set<String> synonyms, ConceptDefinitionComponent cdc) {
    for (String syn : synonyms) {
      // This is a synonym - but we don't know the language
      ConceptDefinitionDesignationComponent cddc = cdc.addDesignation();
      cddc.setValue(syn);
      cddc.setUse(new Coding("http://snomed.info/sct", "900000000000013009", 
              "Synonym (core metadata concept)"));
    }
  }
  
}