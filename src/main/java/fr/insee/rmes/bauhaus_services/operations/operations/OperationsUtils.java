package fr.insee.rmes.bauhaus_services.operations.operations;

import java.io.IOException;

import fr.insee.rmes.persistance.sparql_queries.operations.series.SeriesQueries;
import fr.insee.rmes.utils.EncodingType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.insee.rmes.bauhaus_services.Constants;
import fr.insee.rmes.bauhaus_services.operations.documentations.DocumentationsUtils;
import fr.insee.rmes.bauhaus_services.operations.famopeserind_utils.FamOpeSerIndUtils;
import fr.insee.rmes.bauhaus_services.operations.series.SeriesUtils;
import fr.insee.rmes.bauhaus_services.rdf_utils.ObjectType;
import fr.insee.rmes.bauhaus_services.rdf_utils.RdfService;
import fr.insee.rmes.bauhaus_services.rdf_utils.RdfUtils;
import fr.insee.rmes.config.Config;
import fr.insee.rmes.config.swagger.model.IdLabelTwoLangs;
import fr.insee.rmes.exceptions.ErrorCodes;
import fr.insee.rmes.exceptions.RmesException;
import fr.insee.rmes.exceptions.RmesNotAcceptableException;
import fr.insee.rmes.exceptions.RmesNotFoundException;
import fr.insee.rmes.exceptions.RmesUnauthorizedException;
import fr.insee.rmes.model.ValidationStatus;
import fr.insee.rmes.model.operations.Operation;
import fr.insee.rmes.model.operations.documentations.MSD;
import fr.insee.rmes.persistance.ontologies.INSEE;
import fr.insee.rmes.persistance.sparql_queries.operations.documentations.DocumentationsQueries;
import fr.insee.rmes.persistance.sparql_queries.operations.operations.OperationsQueries;

@Component
public class OperationsUtils extends RdfService{

	static final Logger logger = LogManager.getLogger(OperationsUtils.class);

	@Autowired
	private FamOpeSerIndUtils famOpeSerIndUtils;

	@Autowired
	private SeriesUtils seriesUtils;

	@Autowired
	private DocumentationsUtils documentationsUtils;

	@Autowired
	private OperationPublication operationPublication;

	public Operation getOperationById(String id) throws RmesException {
		return buildOperationFromJson(getOperationJsonById(id));
	}

	public JSONObject getOperationJsonById(String id) throws RmesException {
		JSONObject operation = repoGestion.getResponseAsObject(OperationsQueries.operationQuery(id));
		getOperationSeries(id, operation);
		return operation;
	}

	private void getOperationSeries(String id, JSONObject operation) throws RmesException {
		JSONObject series = repoGestion.getResponseAsObject(OperationsQueries.seriesQuery(id));
		JSONArray creators = repoGestion.getResponseAsJSONList(SeriesQueries.getCreatorsById(series.getString("id")));
		series.put(Constants.CREATORS, creators);
		operation.put("series", series);
	}
	
	public IRI getSeriesUri(String idOperation) throws RmesException{
		JSONObject series = repoGestion.getResponseAsObject(OperationsQueries.seriesQuery(idOperation));
		if (series != null && series.has("id"))		return RdfUtils.objectIRI(ObjectType.SERIES, series.getString("id"));
		return null;
	}

	private Operation buildOperationFromJson(JSONObject operationJson) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);		

		Operation operation = new Operation();

		IdLabelTwoLangs series = famOpeSerIndUtils.buildIdLabelTwoLangsFromJson(operationJson.getJSONObject("series"));

		operation.setId(operationJson.getString(Constants.ID));
		if(operationJson.has(Constants.PREF_LABEL_LG1)) {
			operation.setPrefLabelLg1(operationJson.getString(Constants.PREF_LABEL_LG1));
		}
		if(operationJson.has(Constants.PREF_LABEL_LG2)) {
			operation.setPrefLabelLg2(operationJson.getString(Constants.PREF_LABEL_LG2)); 
		}
		if(operationJson.has(Constants.ALT_LABEL_LG1)) {
			operation.setAltLabelLg1(operationJson.getString(Constants.ALT_LABEL_LG1));
		}
		if(operationJson.has(Constants.ALT_LABEL_LG2)) {
			operation.setAltLabelLg2(operationJson.getString(Constants.ALT_LABEL_LG2));
		}
		operation.setSeries(series);
		if(operationJson.has(Constants.ID_SIMS)) {
			operation.setIdSims(operationJson.getString(Constants.ID_SIMS));
		}
		return operation;
	}

	/**
	 * CREATE
	 * @param body
	 * @return
	 * @throws RmesException
	 */
	public String setOperation(String body) throws RmesException {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		String id = famOpeSerIndUtils.createId();
		Operation operation = new Operation();
		try {
			operation = mapper.readValue(body, Operation.class);
		} catch (IOException e) {
			logger.error(e.getMessage());
		}
		operation.setId(id);
		// Tester l'existence de la série
		String idSeries= operation.getSeries().getId();
		if (! famOpeSerIndUtils.checkIfObjectExists(ObjectType.SERIES,idSeries)) {
			throw new RmesNotFoundException(ErrorCodes.OPERATION_UNKNOWN_SERIES,"Unknown series: ",idSeries) ;
		}
		// Tester que la série n'a pas de Sims
		if (seriesUtils.hasSims(idSeries)){
			throw new RmesNotAcceptableException(ErrorCodes.SERIES_OPERATION_OR_SIMS,"A series cannot have both a Sims and Operation(s)", 
					seriesUtils.getSeriesJsonById(idSeries, EncodingType.MARKDOWN).getString(Constants.PREF_LABEL_LG1)+" ; "+operation.getPrefLabelLg1());
		}
		IRI seriesURI = RdfUtils.objectIRI(ObjectType.SERIES,idSeries);
		// Vérifier droits
		if(!stampsRestrictionsService.canCreateOperation(seriesURI)) {
			throw new RmesUnauthorizedException(ErrorCodes.OPERATION_CREATION_RIGHTS_DENIED, "Only an admin or a series manager can create a new operation.");
		}
		createRdfOperation(operation, seriesURI, ValidationStatus.UNPUBLISHED);
		logger.info("Create operation : {} - {}" , operation.getId() , operation.getPrefLabelLg1());

		return operation.getId();
	}

	/**
	 * UPDATE
	 * @param id
	 * @param body
	 * @return
	 * @throws RmesException
	 */
	public String setOperation(String id, String body) throws RmesException {
		IRI seriesURI=getSeriesUri(id);
		if(!stampsRestrictionsService.canModifyOperation(seriesURI)) {
			throw new RmesUnauthorizedException(ErrorCodes.OPERATION_MODIFICATION_RIGHTS_DENIED, "Only authorized users can modify operations.");
		}

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		Operation operation = new Operation(id);
		try {
			operation = mapper.readerForUpdating(operation).readValue(body);
		} catch (IOException e) {
			logger.error(e.getMessage());
		}

		String status= famOpeSerIndUtils.getValidationStatus(id);
		if(status.equals(ValidationStatus.UNPUBLISHED.getValue()) || status.equals(Constants.UNDEFINED)) {
			createRdfOperation(operation,null,ValidationStatus.UNPUBLISHED);
		} else {
			createRdfOperation(operation,null,ValidationStatus.MODIFIED);
		}
		logger.info("Update operation : {} - {}" , operation.getId() , operation.getPrefLabelLg1());
		return operation.getId();
	}

	private void createRdfOperation(Operation operation, IRI serieUri, ValidationStatus newStatus) throws RmesException {
		Model model = new LinkedHashModel();
		IRI operationURI = RdfUtils.objectIRI(ObjectType.OPERATION,operation.getId());
		/*Const*/
		model.add(operationURI, RDF.TYPE, INSEE.OPERATION, RdfUtils.operationsGraph());
		/*Required*/
		model.add(operationURI, SKOS.PREF_LABEL, RdfUtils.setLiteralString(operation.getPrefLabelLg1(), Config.LG1), RdfUtils.operationsGraph());
		model.add(operationURI, INSEE.VALIDATION_STATE, RdfUtils.setLiteralString(newStatus.toString()), RdfUtils.operationsGraph());
		/*Optional*/
		RdfUtils.addTripleString(operationURI, SKOS.PREF_LABEL, operation.getPrefLabelLg2(), Config.LG2, model, RdfUtils.operationsGraph());
		RdfUtils.addTripleString(operationURI, SKOS.ALT_LABEL, operation.getAltLabelLg1(), Config.LG1, model, RdfUtils.operationsGraph());
		RdfUtils.addTripleString(operationURI, SKOS.ALT_LABEL, operation.getAltLabelLg2(), Config.LG2, model, RdfUtils.operationsGraph());

		if (serieUri != null) {
			//case CREATION : link operation to series
			RdfUtils.addTripleUri(operationURI, DCTERMS.IS_PART_OF, serieUri, model, RdfUtils.operationsGraph());
			RdfUtils.addTripleUri(serieUri, DCTERMS.HAS_PART, operationURI, model, RdfUtils.operationsGraph());
		}

		repoGestion.keepHierarchicalOperationLinks(operationURI,model);
		repoGestion.loadSimpleObject(operationURI, model);
	}


	public String setOperationValidation(String id)  throws RmesException  {
		Model model = new LinkedHashModel();

		//TODO: check : is it the Series id or the Ope id ?
		IRI seriesURI = getSeriesUri(id);
		if(!stampsRestrictionsService.canModifyOperation(seriesURI)) {
			throw new RmesUnauthorizedException(ErrorCodes.OPERATION_MODIFICATION_RIGHTS_DENIED, "Only authorized users can modify operations.");
		}

		//PUBLISH
		operationPublication.publishOperation(id);

		//UPDATE GESTION TO MARK AS PUBLISHED
		IRI operationURI = RdfUtils.objectIRI(ObjectType.OPERATION, id);
		model.add(operationURI, INSEE.VALIDATION_STATE, RdfUtils.setLiteralString(ValidationStatus.VALIDATED), RdfUtils.operationsGraph());
		model.remove(operationURI, INSEE.VALIDATION_STATE, RdfUtils.setLiteralString(ValidationStatus.UNPUBLISHED), RdfUtils.operationsGraph());
		model.remove(operationURI, INSEE.VALIDATION_STATE, RdfUtils.setLiteralString(ValidationStatus.MODIFIED), RdfUtils.operationsGraph());
		logger.info("Validate operation : {}", operationURI);
		repoGestion.objectValidation(operationURI, model);

		return id;
	}



	public MSD getMSD() throws RmesException {
		//		String resQuery = repoGestion.getResponseAsArray(DocumentationsQueries.msdQuery()).toString();		
		return documentationsUtils.buildMSDFromJson(repoGestion.getResponseAsArray(DocumentationsQueries.msdQuery()));

	}

}
