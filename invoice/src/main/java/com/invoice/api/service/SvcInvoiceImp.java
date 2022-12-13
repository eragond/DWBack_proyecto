package com.invoice.api.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.invoice.api.dto.ApiResponse;
import com.invoice.api.dto.DtoCustomer;
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
    	// Deberia Verificar RFC, peeero chance no lo haga :U
    	
    	// Consultar el carrito del cliente, sino tiene articulos mandar error
    	List<Cart> lcar = repoCart.findByRfcAndStatus(rfc, 1);
    	if(lcar.isEmpty())
    		throw new ApiException(HttpStatus.NOT_FOUND, "cart has no items");
    	
    	// Generar la nueva factura
    	Invoice inv = repo.save(new Invoice(rfc, 0.0, 0.0, 0.0, LocalDateTime.now(), 0));
    	log.info(inv.toString());
    	
    	Double total_inv = 0.0; //Para contar el total de la factura
    	Double taxrate = 0.16; //Porcentaje de impuestos
    	
    	// Por cada producto, generar un item de la factura
    	for(Cart c: lcar) {
        	// Obtenemos gtin del producto
    		String gtin = c.getGtin();
    		
    		// Buscar el producto con el cliente feign
    		DtoProduct prod = getProduct(gtin);
    		
    		// Configuramos total,  taxes, subtotal y precio unitario
    		Double unit_price = prod.getPrice();
    		Integer n_prod = c.getQuantity();
    		Double total = unit_price * n_prod;
    		total_inv += total;
    		Double taxes = total * taxrate;
    		Double subtotal = total - taxes;
    		Item itm = new Item(inv.getInvoice_id(), gtin, n_prod, unit_price, subtotal, taxes, total, 0);
    		repoItem.save(itm); // Guardamos
    		
    		// Update del stock del producto
    		updateProductStock(gtin, n_prod);
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
    
    private DtoProduct getProduct(String gtin) {
    	try {
            ResponseEntity<DtoProduct> response = productCl.getProduct(gtin);
            if(response.getStatusCode() == HttpStatus.OK)
                return response.getBody();
            else
            	throw new ApiException(HttpStatus.BAD_REQUEST, "unable to retrieve product information");
        }catch(Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unable to retrieve product information");
        }
    }
    
    private boolean updateProductStock(String gtin, Integer stock) {
        try {
            ResponseEntity<DtoProduct> response = productCl.updateProductStock(gtin, stock);
            if(response.getStatusCode() == HttpStatus.OK)
                return true;
            else
                return false;
        }catch(feign.codec.DecodeException e) {
        	// Hay un error de codificacion en la respuesta del cliente de feign,
        	// sin embargo no afecta el funcionamiento :7)
            //throw new ApiException(HttpStatus.BAD_REQUEST, "Error de codificacion");
        	return true;
        }catch(Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unable to update product information");
        }
    }

}
