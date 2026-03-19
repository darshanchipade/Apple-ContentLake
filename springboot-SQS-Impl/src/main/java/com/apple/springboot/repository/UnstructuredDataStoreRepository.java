package com.apple.springboot.repository;

import com.apple.springboot.model.UnstructuredDataStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UnstructuredDataStoreRepository extends JpaRepository<UnstructuredDataStore, UUID> {

    Optional<UnstructuredDataStore> findBySourceUriAndHtmlMd5Hash(String sourceUri, String htmlMd5Hash);

}
