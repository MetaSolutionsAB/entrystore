package se.kmr.scam.repository.util;

import java.util.HashMap;
import java.util.Map;

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

public class BNodeRewriter {

	public void rewriteBNodes(Repository r) {
		RepositoryConnection rc = null;
		try {
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
						rc.add(subj, pred, obj, ng);
						rc.remove(oldStmnt, ng);
					}
				}
			}
			rc.commit();
		} catch (RepositoryException re) {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private BNode getNewBNode(Map<String, BNode> m, BNode oldBNode, ValueFactory vf) {
		BNode newBNode = (BNode) m.get(oldBNode.stringValue());
		if (newBNode == null) {
			newBNode = vf.createBNode();
			m.put(oldBNode.stringValue(), newBNode);
		}
		return newBNode;
	}

}