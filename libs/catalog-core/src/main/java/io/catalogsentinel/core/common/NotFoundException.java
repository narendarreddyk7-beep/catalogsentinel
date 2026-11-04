package io.catalogsentinel.core.common;

/** A referenced resource does not exist (HTTP 404). */
public class NotFoundException extends RuntimeException {

    private final String resource;
    private final String id;

    public NotFoundException(String resource, Object id) {
        super(resource + " " + id + " not found");
        this.resource = resource;
        this.id = String.valueOf(id);
    }

    public String resource() {
        return resource;
    }

    public String id() {
        return id;
    }
}
