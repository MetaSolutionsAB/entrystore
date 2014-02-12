/*
 * Copyright (c) 2007-2014 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.deri.tarql;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import org.openjena.atlas.io.IndentedWriter;

import arq.cmdline.ArgDecl;
import arq.cmdline.CmdGeneral;

import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.ResultSetFormatter;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.shared.NotFoundException;
import com.hp.hpl.jena.sparql.algebra.table.TableData;
import com.hp.hpl.jena.sparql.serializer.FmtTemplate;
import com.hp.hpl.jena.sparql.serializer.SerializationContext;
import com.hp.hpl.jena.sparql.util.Utils;
import com.hp.hpl.jena.util.FileManager;

public class tarql extends CmdGeneral {

	public static void main(String... args) {
		new tarql(args).mainRun();
	}

	private final ArgDecl testQueryArg = new ArgDecl(false, "test");
	private final ArgDecl withHeaderArg = new ArgDecl(false, "header");
	private final ArgDecl withoutHeaderArg = new ArgDecl(false, "no-header");
	
	private String queryFile;
	private List<String> csvFiles = new ArrayList<String>();
	private boolean withHeader = false;
	private boolean withoutHeader = false;
	private boolean testQuery = false;
	private Model resultModel = ModelFactory.createDefaultModel();
	
	public tarql(String[] args) {
		super(args);
		getUsage().startCategory("Options");
		add(testQueryArg, "--test", "Show CONSTRUCT template and first rows only (for query debugging)");
		add(withHeaderArg, "--header", "Force use of first row as variable names");
		add(withoutHeaderArg, "--no-header", "Force default variable names (?a, ?b, ...)");
		getUsage().startCategory("Main arguments");
		getUsage().addUsage("query.sparql", "File containing a SPARQL query to be applied to a CSV file");
		getUsage().addUsage("table.csv", "CSV file to be processed; can be omitted if specified in FROM clause");
	}
	
	@Override
    protected String getCommandName() {
		return Utils.className(this);
	}
	
	@Override
	protected String getSummary() {
		return getCommandName() + " query.sparql [table.csv [...]]";
	}

	@Override
	protected void processModulesAndArgs() {
		if (getPositional().isEmpty()) {
			doHelp();
		}
		queryFile = getPositionalArg(0);
		for (int i = 1; i < getPositional().size(); i++) {
			csvFiles.add(getPositionalArg(i));
		}
		if (hasArg(withHeaderArg)) {
			if (csvFiles.isEmpty()) {
				cmdError("Cannot use --header if no input data file specified");
			}
			withHeader = true;
		}
		if (hasArg(withoutHeaderArg)) {
			if (csvFiles.isEmpty()) {
				cmdError("Cannot use --no-header if no input data file specified");
			}
			withoutHeader = true;
		}
		if (hasArg(testQueryArg)) {
			testQuery = true;
		}
	}

	@Override
	protected void exec() {
		try {
			TarqlQuery q = new TarqlParser(queryFile).getResult();
			if (testQuery) {
				q.makeTest();
			}
			if (csvFiles.isEmpty()) {
				executeQuery(q);
			} else {
				for (String csvFile: csvFiles) {
					executeQuery(csvFile, q);
				}
			}
			if (!resultModel.isEmpty()) {
				resultModel.write(System.out, "TURTLE", q.getPrologue().getBaseURI());
			}
		} catch (NotFoundException ex) {
			cmdError("Not found: " + ex.getMessage());
		}
	}

	private void executeQuery(TarqlQuery query) {
		for (Query q: query.getQueries()) {
			Model previousResults = ModelFactory.createDefaultModel();
			previousResults.add(resultModel);
			CSVQueryExecutionFactory.setPreviousResults(previousResults);
			Reader reader = CSVQueryExecutionFactory.createReader(q);
			boolean useColumnHeadersAsVars = CSVQueryExecutionFactory.modifyQueryForColumnHeaders(q);
			TableData table = new CSVToValues(reader, useColumnHeadersAsVars).read();
			processResults(CSVQueryExecutionFactory.create(table, q));
			CSVQueryExecutionFactory.resetPreviousResults();
		}
	}
		
	private void executeQuery(String file, TarqlQuery query) {
		boolean explicitHeaderInfo = withHeader || withoutHeader;

		for (Query q: query.getQueries()) {
			Model previousResults = ModelFactory.createDefaultModel();
			previousResults.add(resultModel);
			CSVQueryExecutionFactory.setPreviousResults(previousResults);

			boolean useColumnHeadersAsVars = withHeader;
			if (!explicitHeaderInfo) {
				//BUG (already in cygri code), if called for multiple csvFiles the query will be modified the first time,
				//for later csvFiles the boolean will be false.
				useColumnHeadersAsVars = CSVQueryExecutionFactory.modifyQueryForColumnHeaders(q);
			}
			TableData table;
			if (isCSV(file)) {
				Reader reader = CSVQueryExecutionFactory.createReader(file+"#2");
				table = new CSVToValues(reader, useColumnHeadersAsVars).read();
			} else {
				int sheetNr = 0;
				if (file.contains("#")) {
					sheetNr = Integer.parseInt(file.substring(file.lastIndexOf("#")));
				}
				table = new XLSToValues(FileManager.get().open(file), useColumnHeadersAsVars, sheetNr).read();
			}
			processResults(CSVQueryExecutionFactory.create(table, q));
			CSVQueryExecutionFactory.resetPreviousResults();
		}
	}
	
	private boolean isCSV(String file) {
		return file.endsWith(".csv");
	}
	
	private void processResults(QueryExecution ex) {
		if (testQuery && ex.getQuery().getConstructTemplate() != null) {
			IndentedWriter out = new IndentedWriter(System.out); 
			new FmtTemplate(out, new SerializationContext(ex.getQuery())).format(ex.getQuery().getConstructTemplate());
			out.flush();
		}
		if (ex.getQuery().isSelectType()) {
			System.out.println(ResultSetFormatter.asText(ex.execSelect()));
		} else if (ex.getQuery().isAskType()) {
			System.out.println(ResultSetFormatter.asText(ex.execSelect()));
		} else if (ex.getQuery().isConstructType()) {
			resultModel.setNsPrefixes(resultModel);
			ex.execConstruct(resultModel);
		} else {
			cmdError("Only query forms CONSTRUCT, SELECT and ASK are supported");
		}
	}
}
