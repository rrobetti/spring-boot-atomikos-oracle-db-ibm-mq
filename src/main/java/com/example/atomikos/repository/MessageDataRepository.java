package com.example.atomikos.repository;

import com.example.atomikos.entity.MessageData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageDataRepository extends JpaRepository<MessageData, Long> {
}
