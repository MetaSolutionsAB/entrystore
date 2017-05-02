/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;

import com.hp.hpl.jena.shared.JenaException;
import com.hp.hpl.jena.sparql.algebra.table.TableData;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.binding.Binding;

public class XLSToValues extends CSVToValues {
	
	private FormulaEvaluator evaluator;
	private DataFormatter formatter;
	private int sheet = 0;
	private InputStream is;

	public XLSToValues(InputStream is, boolean varsFromHeader) {
		super(null, varsFromHeader);
		this.is = is;
	}

	public XLSToValues(InputStream is, boolean varsFromHeader, int sheetNr) {
		super(null, varsFromHeader);
		this.is = is;
		this.sheet = sheetNr;
	}
	
	private String[] getRow(Row row) {
        int i=0;//String array
        String[] csvdata = new String[row.getLastCellNum()];
        Iterator<Cell> cellIterator = row.cellIterator();
        while(cellIterator.hasNext()) {
        	
        	Cell cell = cellIterator.next(); //Fetch CELL
        	if(cell.getCellType() != Cell.CELL_TYPE_FORMULA) {
        		csvdata[i] = this.formatter.formatCellValue(cell);
        		}
        	else {
        		csvdata[i] = this.formatter.formatCellValue(cell, this.evaluator);
        	}
        	i=i+1;
        }	
        return csvdata;
	}
	
	
	public TableData read() {
		try {
			List<Binding> bindings = new ArrayList<Binding>();		        

			// Read workbook into HSSFWorkbook
			HSSFWorkbook workbook = new HSSFWorkbook(this.is); 
	        HSSFSheet sheet = workbook.getSheetAt(this.sheet);
	        this.evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            this.formatter = new DataFormatter(true);
            
	        // To iterate over the rows
	        Iterator<Row> rowIterator = sheet.iterator();

			String[] row;
			try {
				if (varsFromHeader) {
					while (rowIterator.hasNext()) {
						row = getRow(rowIterator.next());						
						boolean foundValidColumnName = false;
						for (int i = 0; i < row.length; i++) {
							if (toVar(row[i]) == null) continue;
							foundValidColumnName = true;
						}
						// If row was empty or didn't contain anything usable
						// as column name, then try next row
						if (!foundValidColumnName) continue;
						for (int i = 0; i < row.length; i++) {
							Var var = toVar(row[i]);
							if (var == null || vars.contains(var) || var.equals(TarqlQuery.ROWNUM)) {
								getVar(i);
							} else {
								vars.add(var);
							}
						}
						break;
					}
				}
				rownum = 1;
				while (rowIterator.hasNext()) {
					row = getRow(rowIterator.next());					
					// Skip rows without data
					if (isEmpty(row)) continue;
					bindings.add(toBinding(row));
					rownum++;
				}
				
				vars.add(TarqlQuery.ROWNUM);
				//Make sure variables exists for all columns even if no data is available, otherwise ARQ will complain.
				for(int i=0;i<vars.size();i++) {
					if (vars.get(i) == null) {
						getVar(i);
					}
				}
				return new TableData(vars, bindings);
			} finally {
				this.is.close();
			}
		} catch (IOException ex) {
			throw new JenaException(ex);
		}
	}	
}