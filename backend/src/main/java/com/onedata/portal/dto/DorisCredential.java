package com.onedata.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Doris用户凭据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DorisCredential {
    
    private String username;
    
    private String password;
}
