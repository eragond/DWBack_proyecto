package com.invoice.api.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.invoice.api.dto.ApiResponse;
import com.invoice.api.dto.DtoProduct;
import com.invoice.api.entity.Cart;
import com.invoice.api.entity.Invoice;
import com.invoice.api.entity.Item;
import com.invoice.api.repository.RepoCart;
import com.invoice.api.repository.RepoInvoice;
import com.invoice.api.repository.RepoItem;
import com.invoice.configuration.client.ProductClient;
import com.invoice.exception.ApiException;

@Service
public class SvcInvoiceImp implements SvcInvoice {
	
	private static final Logger log = LoggerFactory.getLogger(SvcInvoiceImp.class);

    @Autowired
    RepoInvoice repo;
    
    @Autowired
    RepoCart repoCart;

    @Autowired
    RepoItem repoItem;
    
    @Autowired
    ProductClient productCl;

    @Override
    public List<Invoice> getInvoices(String rfc) {
        return repo.findByRfcAndStatus(rfc, 1);
    }

    @Override
    public List<Item> getInvoiceItems(Integer invoice_id) {
        return repoItem.getInvoiceItems(invoice_id);
    }

    @Override
    public ApiResponse generateInvoice(String rfc) {
    	// Verificar RFC
    	// TODO
    	
    	// Consultar el carrito del cliente, sino tiene articulos mandar error
    	List<Cart> lcar = repoCart.findByRfcAndStatus(rfc, 1);
    	if(lcar.isEmpty())
    		throw new ApiException(HttpStatus.NOT_FOUND, "cart has no items");
    	
    	// Generar la nueva factura
    	Invoice inv = repo.save(new Invoice(rfc, 0.0, 0.0, 0.0, LocalDateTime.now(), 1));
    	log.info(inv.toString());
    
    	Double total_inv = 0.0; //Para contar el total de la factura
    	Double taxrate = 0.16; //Porcentaje de impuestos
    	
    	// Por cada producto, generar un item de la factura
    	for(Cart c: lcar) {
        	// Obtenemos gtin del producto
    		String gtin = c.getGtin();
    		
    		// Buscar el producto con el cliente feign
    		DtoProduct prod = productCl.getProduct(gtin).getBody();
    		
    		// Configuramos total,  taxes, subtotal y precio unitario
    		Double unit_price = prod.getPrice();
    		Integer n_prod = c.getQuantity();
    		Double total = unit_price * n_prod;
    		total_inv += total;
    		Double taxes = total * taxrate;
    		Double subtotal = total - taxes;
    		Item itm = new Item(inv.getInvoice_id(), gtin, n_prod, unit_price, subtotal, taxes, total, 1);
    		repoItem.save(itm); // Guardamos
    		
    		// Update del stock del producto
    		productCl.updateProductStock(gtin, prod.getStock() - n_prod);
    	}
		
    	// Modificar factura
    	inv.setTotal(total_inv);
    	inv.setTaxes(total_inv * taxrate);
    	inv.setSubtotal(total_inv - (total_inv * taxrate));
    	
    	// Vaciar el carrito del cliente
    	repoCart.clearCart(rfc);
    	
    	// Meter factura a la base de datos
    	repo.save(inv);

        return new ApiResponse("invoice generated");
    }

}
