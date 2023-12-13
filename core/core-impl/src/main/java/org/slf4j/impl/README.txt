It was necessary to fork the classes in this folder because SolrJ depends on it and they were only included in log4j-slf4j-impl which conflicts with log4j-slf4j2-impl.

These may be removed in the future.
