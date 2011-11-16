package se.kmr.scam.repository;

/**
 * @author Hannes Ebner
 */
public enum RepositoryEvent {

	All,
	EntryCreated,
	EntryUpdated, // TODO event firing only implemented for EntryImpl.setGraph()
	EntryDeleted,
	MetadataUpdated,
	ExternalMetadataUpdated,
	ExtractedMetadataUpdated, // TODO event firing not implemented yet
	ResourceUpdated, // TODO partially implemented for ListImpl, fully implemented for DataImpl
	ResourceDeleted // TODO partially implemented for ListImpl, fully implemented for DataImpl

}