package org.entrystore.repository.util;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.openrdf.model.BNode;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.nativerdf.NativeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BNodeRewriter {

    private static Logger log = LoggerFactory.getLogger(BNodeRewriter.class);

	public static void rewriteBNodes(Repository r) {
		RepositoryConnection rc = null;
		try {
            Set<Statement> toAdd = new HashSet<Statement>();
            Set<Statement> toRemove = new HashSet<Statement>();
			rc = r.getConnection();
			rc.setAutoCommit(false);
			ValueFactory vf = rc.getValueFactory();
			RepositoryResult<Resource> ngs = rc.getContextIDs();
			while (ngs.hasNext()) {
				Resource ng = ngs.next();
				RepositoryResult<Statement> oldStmnts = rc.getStatements(null, null, null, false, ng);
				Map<String, BNode> old2newBNode = new HashMap<String, BNode>();
				while (oldStmnts.hasNext()) {
					boolean replace = false;
					Statement oldStmnt = oldStmnts.next();
					Resource subj = oldStmnt.getSubject();
					URI pred = oldStmnt.getPredicate();
					Value obj = oldStmnt.getObject();
					if (subj instanceof BNode) {
						subj = getNewBNode(old2newBNode, (BNode) subj, vf);
						replace = true;
					}
					if (obj instanceof BNode) {
						obj = getNewBNode(old2newBNode, (BNode) obj, vf);
						replace = true;
					}
					if (replace) {
                        Statement newStmnt = vf.createStatement(subj, pred, obj, ng);
						toAdd.add(newStmnt);
                        toRemove.add(oldStmnt);
                        log.info("Replacing " + oldStmnt + " with " + newStmnt);
					}
				}
			}
            log.info("Adding new statements");
            rc.add(toAdd);
            log.info("Removing old statements");
            rc.remove(toRemove);
            log.info("Committing transaction");
			rc.commit();
		} catch (RepositoryException re) {
			log.error(re.getMessage());
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
			}
		}
	}

	private static BNode getNewBNode(Map<String, BNode> m, BNode oldBNode, ValueFactory vf) {
		BNode newBNode = (BNode) m.get(oldBNode.stringValue());
		if (newBNode == null) {
			newBNode = vf.createBNode();
			m.put(oldBNode.stringValue(), newBNode);
		}
		return newBNode;
	}

	public static void main(String[] args) throws RepositoryException {
		NativeStore store = new NativeStore(new File("PATH-TO-REPOSITORY"));
		Repository repository = new SailRepository(store);
		repository.initialize();
		rewriteBNodes(repository);
		repository.shutDown();
	}

}