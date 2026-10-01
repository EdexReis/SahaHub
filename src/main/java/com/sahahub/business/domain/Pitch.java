package com.sahahub.business.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Kiralanabilir saha.
 * <ul>
 * <li>slotMinutes: müşterinin seçebileceği maç süresi (ör. 60 dk).</li>
 * <li>slotStepMinutes: başlangıç saatleri açılıştan itibaren bu aralıkla dizilir.</li>
 * <li>bufferMinutes: her rezervasyondan sonra saha hazırlığı için ayrılan süre;
 * sonraki rezervasyon bu süre bitmeden başlayamaz.</li>
 * </ul>
 */
@Entity
@Table(name = "pitch")
public class Pitch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(nullable = false)
	private String name;

	private String description;

	@Column(name = "capacity_players", nullable = false)
	private int capacityPlayers;

	@Column(name = "length_m")
	private Integer lengthM;

	@Column(name = "width_m")
	private Integer widthM;

	@Column(nullable = false)
	private boolean indoor;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Surface surface;

	@Column(name = "has_lighting", nullable = false)
	private boolean hasLighting;

	@Column(name = "has_parking", nullable = false)
	private boolean hasParking;

	@Column(name = "has_shower", nullable = false)
	private boolean hasShower;

	@Column(name = "has_locker_room", nullable = false)
	private boolean hasLockerRoom;

	@Column(name = "slot_minutes", nullable = false)
	private int slotMinutes = 60;

	@Column(name = "slot_step_minutes", nullable = false)
	private int slotStepMinutes = 60;

	@Column(name = "buffer_minutes", nullable = false)
	private int bufferMinutes;

	@Column(name = "base_hourly_price", nullable = false)
	private BigDecimal baseHourlyPrice;

	@Column(nullable = false)
	private String currency = "TRY";

	@Column(name = "photo_path")
	private String photoPath;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Pitch() {
	}

	public Pitch(Long branchId, String name, int capacityPlayers, Surface surface, BigDecimal baseHourlyPrice,
			Instant createdAt) {
		this.branchId = branchId;
		this.name = name;
		this.capacityPlayers = capacityPlayers;
		this.surface = surface;
		this.baseHourlyPrice = baseHourlyPrice;
		this.createdAt = createdAt;
	}

	public void configureSlots(int slotMinutes, int slotStepMinutes, int bufferMinutes) {
		this.slotMinutes = slotMinutes;
		this.slotStepMinutes = slotStepMinutes;
		this.bufferMinutes = bufferMinutes;
	}

	public void describe(String description, Integer lengthM, Integer widthM, boolean indoor) {
		this.description = description;
		this.lengthM = lengthM;
		this.widthM = widthM;
		this.indoor = indoor;
	}

	public void setAmenities(boolean lighting, boolean parking, boolean shower, boolean lockerRoom) {
		this.hasLighting = lighting;
		this.hasParking = parking;
		this.hasShower = shower;
		this.hasLockerRoom = lockerRoom;
	}

	public void changeBasePrice(BigDecimal baseHourlyPrice) {
		this.baseHourlyPrice = baseHourlyPrice;
	}

	public Duration buffer() {
		return Duration.ofMinutes(bufferMinutes);
	}

	public Long getId() {
		return id;
	}

	public Long getBranchId() {
		return branchId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public int getCapacityPlayers() {
		return capacityPlayers;
	}

	public Integer getLengthM() {
		return lengthM;
	}

	public Integer getWidthM() {
		return widthM;
	}

	public boolean isIndoor() {
		return indoor;
	}

	public Surface getSurface() {
		return surface;
	}

	public boolean isHasLighting() {
		return hasLighting;
	}

	public boolean isHasParking() {
		return hasParking;
	}

	public boolean isHasShower() {
		return hasShower;
	}

	public boolean isHasLockerRoom() {
		return hasLockerRoom;
	}

	public int getSlotMinutes() {
		return slotMinutes;
	}

	public int getSlotStepMinutes() {
		return slotStepMinutes;
	}

	public int getBufferMinutes() {
		return bufferMinutes;
	}

	public BigDecimal getBaseHourlyPrice() {
		return baseHourlyPrice;
	}

	public String getCurrency() {
		return currency;
	}

	public String getPhotoPath() {
		return photoPath;
	}

	public boolean isActive() {
		return active;
	}

}
