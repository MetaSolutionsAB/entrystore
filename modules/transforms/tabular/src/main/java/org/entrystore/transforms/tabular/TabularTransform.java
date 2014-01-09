package org.entrystore.transforms.tabular;

import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.ResultSetFormatter;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.shared.NotFoundException;
import com.hp.hpl.jena.sparql.algebra.table.TableData;
import org.deri.tarql.CSVQueryExecutionFactory;
import org.deri.tarql.CSVToValues;
import org.deri.tarql.TarqlParser;
import org.deri.tarql.TarqlQuery;
import org.deri.tarql.XLSToValues;
import org.entrystore.transforms.Transform;
import org.entrystore.transforms.TransformParameters;
import org.openrdf.model.Graph;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.rio.ntriples.NTriplesParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;

@TransformParameters(type="tabular", extensions={"csv", "xls"})
public class TabularTransform extends Transform {

	private static Logger log = LoggerFactory.getLogger(TabularTransform.class);

	public Graph transform(InputStream data, String mimetype) {
		try {
			TableData table;
			if (isCSV(mimetype)) {
				Reader reader = CSVQueryExecutionFactory.createReader(data);
				table = new CSVToValues(reader, false).read();
			} else {
				//TODO extract sheetNr from args..
				int sheetNr = 0;
				table = new XLSToValues(data, false, sheetNr).read();
			}

			String tarqlstr = getArguments().get("tarqlstring");
			TarqlQuery q = new TarqlParser(new StringReader(tarqlstr)).getResult();
			Model resultModel = ModelFactory.createDefaultModel();
			executeQuery(table, q, resultModel);

			if (!resultModel.isEmpty()) {
				return model2Graph(resultModel, q.getPrologue().getBaseURI());
			}
		} catch (NotFoundException ex) {
			log.error("Not found: " + ex.getMessage());
		}
		return null;
	}

	private boolean isCSV(String mimetype) {
		return mimetype.toLowerCase().contains("csv");
	}

	private Graph model2Graph(Model model, String baseUri) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		model.write(out, "N-TRIPLE", baseUri);
		StatementCollector collector = new StatementCollector();
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream(out.toByteArray());
			NTriplesParser parser = new NTriplesParser();
			parser.setRDFHandler(collector);
			parser.parse(new InputStreamReader(bais), "");
		} catch (RDFHandlerException rdfe) {
			log.error(rdfe.getMessage());
		} catch (RDFParseException rdfpe) {
			log.error(rdfpe.getMessage());
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		}
		return new org.openrdf.model.impl.LinkedHashModel(collector.getStatements());
	}

	private void executeQuery(TableData table, TarqlQuery query, Model resultModel) {
		for (Query q : query.getQueries()) {
			Model previousResults = ModelFactory.createDefaultModel();
			previousResults.add(resultModel);
			CSVQueryExecutionFactory.setPreviousResults(previousResults);
			processResults(CSVQueryExecutionFactory.create(table, q), resultModel);
			CSVQueryExecutionFactory.resetPreviousResults();
		}
	}

	private void processResults(QueryExecution ex, Model resultModel) {
		if (ex.getQuery().isSelectType()) {
			log.info(ResultSetFormatter.asText(ex.execSelect()));
		} else if (ex.getQuery().isAskType()) {
			log.info(ResultSetFormatter.asText(ex.execSelect()));
		} else if (ex.getQuery().isConstructType()) {
			resultModel.setNsPrefixes(resultModel);
			ex.execConstruct(resultModel);
		} else {
			log.error("Only query forms CONSTRUCT, SELECT and ASK are supported");
		}
	}

}