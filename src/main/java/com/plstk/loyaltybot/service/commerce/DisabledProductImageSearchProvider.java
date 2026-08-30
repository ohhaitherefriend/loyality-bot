package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.ImageCandidate;
import com.plstk.loyaltybot.entity.commerce.Product;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class DisabledProductImageSearchProvider implements ProductImageSearchProvider {

    @Override
    public List<ImageCandidate> search(Product product) {
        return Collections.emptyList();
    }
}
