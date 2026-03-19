package com.apple.springboot.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A specific role-based piece of text content from a section")
public class ContentRoleDto {
    private String role;
    private String text;
    /** Optional URL — populated for CTA/call-to-action fields so the UI can render them as links. */
    private String href;

    public ContentRoleDto() {}
    
    public ContentRoleDto(String role, String text) {
        this.role = role;
        this.text = text;
    }

    public ContentRoleDto(String role, String text, String href) {
        this.role = role;
        this.text = text;
        this.href = href;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
}
