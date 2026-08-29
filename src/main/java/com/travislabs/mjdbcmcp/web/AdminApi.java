package com.travislabs.mjdbcmcp.web;

import com.travislabs.mjdbcmcp.config.AppProperties;
import com.travislabs.mjdbcmcp.datasource.Capability;
import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.DatasourceService;
import com.travislabs.mjdbcmcp.datasource.DriverRegistry;
import com.travislabs.mjdbcmcp.mcp.ToolSurface;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST surface behind the Admin Interface. */
@RestController
@RequestMapping("/api")
public class AdminApi {

    private final DatasourceService datasources;
    private final DriverRegistry drivers;
    private final ToolSurface surface;
    private final McpSyncServer mcpServer;
    private final AppProperties props;

    public AdminApi(DatasourceService datasources, DriverRegistry drivers,
                    ToolSurface surface, McpSyncServer mcpServer, AppProperties props) {
        this.datasources = datasources;
        this.drivers = drivers;
        this.surface = surface;
        this.mcpServer = mcpServer;
        this.props = props;
    }

    @GetMapping("/datasources")
    public List<DatasourceDto> list() {
        return datasources.findAll().stream().map(DatasourceDto::of).toList();
    }

    @PostMapping("/datasources")
    @ResponseStatus(HttpStatus.CREATED)
    public DatasourceDto create(@Valid @RequestBody DatasourceDto dto) {
        DatasourceDto created = DatasourceDto.of(datasources.create(dto.toDomain()));
        // Capabilities decide which tools exist, so a new Datasource can change the surface.
        surface.refresh();
        return created;
    }

    @PutMapping("/datasources/{id}")
    public DatasourceDto update(@PathVariable long id, @Valid @RequestBody DatasourceDto dto) {
        DatasourceDto updated = DatasourceDto.of(datasources.update(id, dto.toDomain()));
        surface.refresh();
        return updated;
    }

    @DeleteMapping("/datasources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        datasources.delete(id);
        surface.refresh();
    }

    /**
     * Opens a one-shot pool against the submitted settings. A saved Datasource may be tested by id
     * with a blank password, which reuses the stored one.
     */
    @PostMapping("/datasources/test")
    public Map<String, Object> test(@Valid @RequestBody DatasourceDto dto) {
        Datasource candidate = dto.toDomain();
        if ((candidate.password() == null || candidate.password().isEmpty()) && dto.id() != null) {
            candidate = candidate.withPassword(
                    datasources.findById(dto.id()).map(Datasource::password).orElse(null));
        }
        try {
            datasources.probe(candidate);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @GetMapping("/pools")
    public List<Map<String, Object>> pools() {
        return datasources.poolStats().stream()
                .map(p -> Map.<String, Object>of(
                        "datasource", p.datasource(),
                        "total", p.total(),
                        "active", p.active(),
                        "idle", p.idle(),
                        "awaiting", p.awaiting(),
                        "max", p.max()))
                .toList();
    }

    @GetMapping("/drivers")
    public Map<String, Object> drivers() {
        return Map.of(
                "registered", drivers.registeredDrivers(),
                "external", drivers.externalDrivers(),
                "directory", props.driversDir().toString());
    }

    @GetMapping("/server")
    public Map<String, Object> server() {
        return Map.of(
                "name", props.mcp().serverName(),
                "endpoint", props.mcp().endpoint(),
                "configDir", props.configDir().toString(),
                "capabilities", Arrays.stream(Capability.values()).map(Capability::wireName).toList(),
                // The surface follows the Capabilities granted, so the UI shows what an agent sees.
                "tools", mcpServer.listTools().stream().map(t -> Map.of(
                        "name", t.name(),
                        "description", t.description() == null ? "" : t.description())).toList(),
                "warnings", surface.configurationWarnings());
    }
}
