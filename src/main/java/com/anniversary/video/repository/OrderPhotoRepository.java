package com.anniversary.video.repository;

import com.anniversary.video.domain.OrderPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderPhotoRepository extends JpaRepository<OrderPhoto, Long> {
    List<OrderPhoto> findByOrderIdOrderBySortOrder(Long orderId);
}
